param([switch]$SmokeOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

function Assert-Equal($actual, $expected, [string]$label) {
    if ($actual -ne $expected) { throw "$label failed: expected $expected, got $actual" }
}

function Wait-For([scriptblock]$operation) {
    # Local JVM/Kafka startup and asynchronous projections may require up to two minutes.
    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ([DateTime]::UtcNow -lt $deadline) {
        try { return (& $operation) }
        catch { Start-Sleep -Seconds 2 }
    }
    throw 'Readiness or asynchronous processing timed out; inspect docker compose logs.'
}

function Api([string]$method, [string]$path, [string]$token, $body = $null, [string]$key = '') {
    $headers = @{ Authorization = "Bearer $token" }
    if ($key) { $headers['Idempotency-Key'] = $key }
    $arguments = @{ Uri = "http://localhost:8080$path"; Method = $method; Headers = $headers }
    if ($null -ne $body) {
        $arguments.Body = ConvertTo-Json -InputObject $body -Depth 8 -Compress
        $arguments.ContentType = 'application/json'
    }
    Invoke-RestMethod @arguments
}

function Expect-Status([scriptblock]$operation, [int]$status) {
    try { & $operation | Out-Null }
    catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq $status) { return }
        throw
    }
    throw "Expected HTTP $status"
}

if (!(Test-Path '.env')) {
    $databasePassword = [Guid]::NewGuid().ToString('N') + 'Db9A'
    $identityPassword = [Guid]::NewGuid().ToString('N') + 'Id9A'
    [IO.File]::WriteAllText((Join-Path $root '.env'), "FINPAY_DB_PASSWORD=$databasePassword`nFINPAY_IDP_PASSWORD=$identityPassword`n",
        (New-Object Text.UTF8Encoding($false)))
}
$settings = @{}
Get-Content '.env' | ForEach-Object {
    if ($_ -match '^(FINPAY_DB_PASSWORD|FINPAY_IDP_PASSWORD)=(.+)$') { $settings[$matches[1]] = $matches[2] }
}
if (!$settings['FINPAY_IDP_PASSWORD']) { throw 'FINPAY_IDP_PASSWORD is missing from .env' }

if (!$SmokeOnly) {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Start a Docker-compatible Linux engine and install Docker CLI/Compose. Docker Engine on Linux is a no-subscription option.' }
    .\gradlew test bootJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    docker compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose startup failed.' }
}

$adminToken = Wait-For {
    (Invoke-RestMethod -Method Post -Uri 'http://localhost:8180/realms/master/protocol/openid-connect/token' -Body @{
        client_id = 'admin-cli'; grant_type = 'password'; username = 'admin'; password = $settings['FINPAY_IDP_PASSWORD']; scope = 'openid'
    }).access_token
}
$adminHeaders = @{ Authorization = "Bearer $adminToken" }

function Create-DemoUser([string]$prefix, [switch]$Admin) {
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $username = "$prefix-$suffix@example.test"
    $password = [Guid]::NewGuid().ToString('N') + 'Demo9A'
    $user = @{username = $username; email = $username; emailVerified = $true; firstName = $prefix; lastName = 'Demo'; enabled = $true;
        credentials = @(@{type = 'password'; value = $password; temporary = $false})}
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://localhost:8180/admin/realms/finpay/users' `
        -Headers $adminHeaders -ContentType 'application/json' -Body (ConvertTo-Json $user -Depth 6)
    $userId = ($response.Headers.Location -split '/')[-1]
    if ($Admin) {
        $role = Invoke-RestMethod -Uri 'http://localhost:8180/admin/realms/finpay/roles/ADMIN' -Headers $adminHeaders
        Invoke-RestMethod -Method Post -Uri "http://localhost:8180/admin/realms/finpay/users/$userId/role-mappings/realm" `
            -Headers $adminHeaders -ContentType 'application/json' -Body (ConvertTo-Json -InputObject @($role)) | Out-Null
    }
    return (Invoke-RestMethod -Method Post -Uri 'http://localhost:8180/realms/finpay/protocol/openid-connect/token' -Body @{
        client_id = 'finpay-local'; grant_type = 'password'; username = $username; password = $password; scope = 'openid'
    }).access_token
}

$alice = Create-DemoUser 'alice'
$bob = Create-DemoUser 'bob'
$operator = Create-DemoUser 'operator' -Admin
$source = Wait-For { Api 'Post' '/api/wallets' $alice }
$target = Wait-For { Api 'Post' '/api/wallets' $bob }
$fundingKey = [Guid]::NewGuid().ToString()
Api 'Post' "/api/wallets/$($source.id)/sandbox-funding" $operator @{amountMinor = 1000000} $fundingKey | Out-Null
Api 'Post' "/api/wallets/$($source.id)/sandbox-funding" $operator @{amountMinor = 1000000} $fundingKey | Out-Null

$key = [Guid]::NewGuid().ToString()
$request = @{sourceWalletId = $source.id; targetWalletId = $target.id; amountMinor = 100000; currency = 'INR'; description = 'SWIGGY dinner'; purpose = 'MERCHANT'}
$payment = Api 'Post' '/api/payments' $alice $request $key
$duplicate = Api 'Post' '/api/payments' $alice $request $key
Assert-Equal $duplicate.id $payment.id 'Payment idempotency'
$settled = Wait-For {
    $current = Api 'Get' "/api/payments/$($payment.id)" $alice
    if ($current.status -eq 'PENDING') { throw 'Still pending' }
    return $current
}
Assert-Equal $settled.status 'SUCCESS' 'Payment settlement'
Assert-Equal (Api 'Get' "/api/wallets/$($source.id)" $alice).balanceMinor 900000 'Sender balance'
Assert-Equal (Api 'Get' "/api/wallets/$($target.id)" $bob).balanceMinor 100000 'Receiver balance'
$request.amountMinor = 100001
Expect-Status { Api 'Post' '/api/payments' $alice $request $key } 409
Expect-Status { Api 'Get' "/api/wallets/$($source.id)" $bob } 404
Expect-Status { Api 'Post' "/api/wallets/$($source.id)/sandbox-funding" $alice @{amountMinor = 1} ([Guid]::NewGuid().ToString()) } 403
Expect-Status { Invoke-RestMethod 'http://localhost:8080/api/payments' } 401

$request.amountMinor = 4000000
$failed = Api 'Post' '/api/payments' $alice $request ([Guid]::NewGuid().ToString())
$rejected = Wait-For {
    $current = Api 'Get' "/api/payments/$($failed.id)" $alice
    if ($current.status -eq 'PENDING') { throw 'Still pending' }
    return $current
}
Assert-Equal $rejected.status 'FAILED' 'Insufficient funds status'
Assert-Equal $rejected.reason 'INSUFFICIENT_FUNDS' 'Insufficient funds reason'
$entries = Wait-For { Api 'Get' "/api/ledger/$($payment.id)" $operator }
Assert-Equal @($entries).Count 2 'Double-entry journal'
Assert-Equal ($entries | Where-Object side -eq 'DEBIT').amount_minor ($entries | Where-Object side -eq 'CREDIT').amount_minor 'Balanced entries'
$month = [DateTime]::UtcNow.ToString('yyyy-MM')
$report = Wait-For {
    $current = Api 'Get' "/api/insights/$month" $alice
    if (@($current.categories).Count -eq 0) { throw 'Projection pending' }
    return $current
}
Assert-Equal $report.categories[0].category 'FOOD' 'Spending category'
Assert-Equal $report.categories[0].amountMinor 100000 'Spending total'
Write-Output 'PASS: payment, replay, funding replay, insufficient funds, authorization, ledger, and spending checks.'
Write-Output 'Demo users and simulated financial history remain in the local volumes. No tokens were printed.'
