# FinPay

FinPay is a standalone Java/Spring Boot payments **sandbox** for learning transaction consistency and event-driven services. It is not connected to banks, holds no real funds, and is not a production-ready or regulated financial product. This directory is independent of startX; no startX build files or proprietary dependencies are used.

## Free local resources

The application requires **no paid API, subscription, or cloud account**. Run the services on hardware you control. Optional AI uses local Ollama and an approved Qwen model; the application rejects public AI-provider endpoints and cloud model names. No API key is needed.

| Concern | Local, no-subscription choice |
|---|---|
| Java/frontend | OpenJDK, Spring Boot, Next.js, React, and community UI packages |
| Database/events/identity | PostgreSQL, Apache Kafka, and Keycloak |
| AI | Ollama with `qwen2.5:1.5b`, or the built-in no-model summary |
| Containers | Docker Engine + Compose on Linux; Podman with Docker-compatible tooling is another option |
| CI | Manually triggered, self-hosted Linux runner; no metered hosted runner is selected |
| Payments/notifications | Simulated wallet funds and in-app notifications; no bank, SMS, or email-provider fees |

Free software does not make hardware, electricity, internet downloads, storage, or optional cloud hosting free. Docker Desktop has organization-dependent licensing; do not assume it is free for corporate use. Model downloads need disk space and bandwidth. Review the [Qwen model license](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct/blob/main/LICENSE) and applicable software licenses for your deployment. No Vercel, managed database, commercial UI component, or paid AI service is required.

## Implemented scope

| Component | Working implementation |
|---|---|
| Identity | Keycloak registration/login, password policy, brute-force protection, CUSTOMER/MERCHANT/ADMIN roles, JWT issuer and audience validation |
| Gateway | Nginx routing, per-IP rate limits, bounded request bodies/timeouts, secure headers |
| Wallet service | One INR wallet per identity, owner-only reads, admin-only simulated funding, atomic transfers, ordered pessimistic row locks |
| Payment service | Owner-scoped UUID idempotency keys, payload conflict detection, asynchronous PENDING → SUCCESS/FAILED, status and paginated outgoing history |
| Accounting | Balanced immutable journal pairs committed alongside wallet updates; separate idempotent ledger projection |
| Messaging | Transactional outbox, broker acknowledgements, persistent inbox/operation deduplication, three partitions, retries, DLTs |
| Risk baseline | Deterministic rejection above ₹50,000, insufficient-funds checks, maximum-balance protection; no trained fraud model |
| Notifications | Asynchronous, persisted in-app payment results; email/SMS/push providers are not connected |
| Intelligence | Rule-based merchant categorization, private UTC monthly spending aggregates, optional local Ollama summaries with bounded calls and deterministic fallback |
| Operations | Actuator health/metrics, Prometheus endpoint, structured JSON logs, container definitions, CI and test harnesses |

Five Spring Boot applications share only technical infrastructure and the version-one event contract. Each service has a **separate PostgreSQL instance and database** in Compose. Keycloak replaces a custom password/JWT issuer; there is no separate user-profile application yet. The gateway is Nginx, not Spring Cloud Gateway.

The wallet/payment write paths use explicit ports and adapters with pure monetary policy. The small read-side services are projection adapters, not full domain aggregates. Persistence deliberately uses parameterized JDBC for explicit locking and SQL invariants; although the Spring Data JPA starter is present, this release does not demonstrate JPA entity relationships or ORM mapping features.

## Financial consistency

```text
Client → Gateway → Payment DB + outbox (one transaction)
                           ↓ Kafka payment command
                  Wallet locks both accounts in UUID order
                  verifies owner, risk, and balance
                  updates both balances + journal + outbox (one transaction)
                           ↓ Kafka outcome/journal
                  Payment status | Ledger | Notifications | Spending
```

Amounts are integer **INR paise**: `100000` means ₹1,000. No floating-point arithmetic is used for money. Wallet balances cannot be negative. A journal pair represents an equal debit and credit by construction. For wallet liabilities, a transfer debits the sender liability and credits the recipient liability. Sandbox funding debits a dedicated clearing account and credits the wallet liability; it is not evidence of a real bank deposit.

* A `202 Accepted` payment response means **PENDING**, not settled. Poll its `Location` URL. An idempotent replay returns the same payment, possibly already terminal; a different payload with the same key returns `409`.
* Idempotency keys are UUIDs scoped to the authenticated user. They are retained without expiry in this version. Reuse the same key after uncertain HTTP outcomes.
* Wallet operations are deduplicated independently of Kafka event IDs. Retries cannot post the same operation twice. Opposing transfers acquire account locks in the same order.
* The outbox is **at-least-once**, not end-to-end exactly-once. A crash after broker acknowledgement but before the database commit causes replay, which consumers must tolerate.
* The authoritative journal lives with wallet balances. A delayed ledger projection must **not** trigger reversal of an already committed transfer. This release intentionally does not implement a cross-service debit/ledger saga.
* If processing reaches a DLT, a payment can remain PENDING until an operator investigates and safely replays the original command. Timeout alone is never interpreted as failure. Do not submit a replacement payment with a new key.
* Database triggers reject ordinary journal updates/deletes. They are not tamper-proof against database owners/superusers. Separate migration/runtime roles, archival, reconciliation, retention, and external audit controls are production prerequisites.

## Quick start on Windows

Prerequisites: JDK **21 or newer**, a Docker-compatible engine running **Linux containers**, Docker CLI/Compose v2, and sufficient memory for five JVMs, databases, Kafka, and Keycloak (start with at least 8–12 GB available, plus memory for optional AI). Docker Engine on Linux is a no-subscription option; Windows users can run it in a Linux VM or use a compatible Podman setup. Use Docker Desktop only if its license terms are appropriate for your organization. The checked-in Gradle wrapper determines the Gradle version. Do not install a separate Gradle distribution.

From this directory:

```powershell
.\gradlew test bootJar --console=plain
.\scripts\run-local.ps1
```

The script generates `.env` with random local database/identity passwords if absent, builds the JARs, starts Compose, creates uniquely named demo users, and checks a transfer end to end. Tokens and generated passwords are never printed. If local PowerShell execution policy blocks scripts, use your organization's approved process rather than disabling machine-wide policy.

The smoke checks cover funding replay, payment replay, conflicting idempotency payloads, insufficient funds, unauthorized reads, forbidden funding, equal ledger entries, and the spending projection. To rerun checks against an existing stack:

```powershell
.\scripts\run-local.ps1 -SmokeOnly
```

Endpoints:

* API gateway: `http://localhost:8080`
* Keycloak: `http://localhost:8180` — administrative username `admin`; the locally generated password is in `.env`
* Realm: `finpay`; development client: `finpay-local`; API audience: `finpay-api`

All host ports bind to loopback. PostgreSQL, Kafka, and application ports are not published. Direct-password grants, Keycloak's development database, plaintext internal traffic, and shared local database password are **local-only conveniences**. Disable direct grants, require TLS, use PKCE/OIDC browser login, separate secrets and database roles, Kafka authentication/ACLs, and a persistent production identity database before any non-local deployment. Email verification/password-reset delivery also requires an SMTP configuration.

Stop without removing data:

```powershell
docker compose down
```

Demo accounts/history remain in named volumes. Changing `.env` does not rotate credentials already stored in volumes. Do not delete volumes unless intentionally discarding all sandbox history.

## API contract

`api/openapi.yml` documents all public business endpoints. Import it into an OpenAPI-compatible client. There is no hosted Swagger UI or frontend yet.

Requests require a bearer token issued by the local realm with audience `finpay-api`. Ownership comes from the JWT subject, never a user-supplied owner ID. Only administrators may fund wallets or inspect ledger entries.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/wallets` | Create or return the caller's wallet |
| GET | `/api/wallets/{id}` | Read caller-owned balance |
| POST | `/api/wallets/{id}/sandbox-funding` | Admin funding; UUID `Idempotency-Key`; disabled outside sandbox configuration |
| POST | `/api/payments` | Initiate transfer/merchant-labelled payment; UUID `Idempotency-Key` |
| GET | `/api/payments/{id}` | Poll caller-owned payment |
| GET | `/api/payments?offset=0` | Up to 50 outgoing payments |
| GET | `/api/ledger/{paymentId}` | Admin-only debit/credit entries |
| GET | `/api/notifications?offset=0` | Up to 50 caller-owned in-app notifications |
| GET | `/api/insights/{YYYY-MM}` | Backend-computed outgoing spending by category |
| POST | `/api/insights/{YYYY-MM}/summary` | Summarize those same private aggregates |

Merchant payments currently use the same wallet transfer mechanism with a `MERCHANT` purpose label. Merchant onboarding, verified merchant ownership, acquiring, settlement, and external providers are not implemented. Balances cannot be debited by a public debit/credit endpoint.

## Optional AI

By default, no AI model or external API is used. Categorization is an explicitly labeled rule baseline. The summary endpoint returns structured totals and a deterministic description when AI is disabled or unavailable. This is the lightest local setup.

For local AI, start the optional Ollama container and download the approved small model once:

```powershell
docker compose --profile ai up -d ollama
docker compose exec ollama ollama pull qwen2.5:1.5b
```

Then set `AI_ENABLED=true` in the ignored `.env`, keep `AI_MODEL=qwen2.5:1.5b`, and recreate the intelligence container:

```powershell
docker compose --profile ai up -d --build intelligence
```

Ollama cloud features are disabled in Compose, and its port is not published. Inference stays on the local Compose network. The standalone Java application's `OLLAMA_BASE_URL` defaults to `http://localhost:11434`; Compose sets it to `http://ollama:11434`. Only loopback, the internal `ollama` hostname, and Docker's host alias are allowed. The approved models are `qwen2.5:1.5b` and `qwen2.5:7b`; the latter needs substantially more memory. CPU inference works without a GPU, but cold or slow models may hit the bounded timeout and return the safe fallback. The API never downloads models automatically.

Only aggregate categories and totals enter the local prompt—not account IDs, JWTs, raw descriptions, or full transaction histories. Model downloads initially require internet access; inference requires no paid API or key. The model receives no database connection or money-moving tool. AI narrative can still be wrong: the separately returned structured totals remain authoritative. The adapter is tested with a local HTTP stub; these tests do not measure actual model quality or hardware performance.

## Verification

```powershell
.\gradlew clean test bootJar --console=plain
.\gradlew integrationTest --console=plain
```

The first command runs unit tests and packages five executable JARs at `<service>/build/libs/app.jar`. The second requires Docker and runs Testcontainers PostgreSQL/Kafka tests; absent Docker is a failure, not a silently skipped integration suite. Test reports are in `<module>/build/reports/tests/`; coverage reports use JaCoCo.

Tests include money/risk boundaries, AI fallback, role mapping, outbox acknowledgement/failure, concurrent payment idempotency, conflicting terminal outcomes, concurrent wallet spending, owner checks, database rollback, journal immutability, and actual broker delivery. The smoke harness adds cross-service HTTP/Kafka checks. Security filter unit tests use a stub decoder; actual realm issuance/validation is exercised by the smoke run, not by those unit tests.

## Operations and failure handling

* Each service exposes `/actuator/health` and readiness/liveness probes internally. `/actuator/prometheus` requires an ADMIN token. Metrics are not routed through the public gateway. Prometheus/Grafana and a secure scrape-identity setup are follow-up work.
* JSON logs are written to stdout. Gateway request IDs are available, but end-to-end OpenTelemetry tracing and centralized log collection are not yet configured.
* The local broker has three partitions and replication factor one: it is not highly available. Production needs an appropriate replicated quorum and ACLs that permit only the payment service to publish commands and the wallet service to publish journal/results.
* DLTs use the original topic name plus `.DLT`. Do not replay blindly: inspect the failure and authoritative wallet operation, then replay the same payload/payment ID after correcting the cause. Monitor pending payments, unpublished outbox age, DLT depth, and projection lag before production use.
* Outbox/inbox/journal retention is unbounded in this version. Define compatible replay windows and archival policies before pruning anything.
* Refunds must be new compensating postings, never journal edits. Provider timeouts must retain an uncertain/PENDING status until reconciliation; this starter does not pretend to implement those workflows.

## Roadmap

1. **Identity and domain expansion:** separate profile/KYC service, verified merchants, permissions, richer account types, JPA mapping examples where appropriate.
2. **External payment workflows:** provider ports, reservation/settlement saga, idempotent refunds, uncertain-outcome reconciliation, reversal audit records, resilience/circuit breakers and bulkheads at real network boundaries.
3. **Financial assurance:** ledger/account reconciliation, tamper-evident audit export, limits/velocity and beneficiary rules, dedicated risk service, administrative review workflows.
4. **Intelligence:** user-approved CSV/PDF ingestion with validation, controlled conversational tools, model-assisted categorization, retrieval over approved FAQs/policies with citations. Vector RAG and statement analysis are not implemented.
5. **Operations:** Redis-backed per-identity limits if needed, email/SMS/push adapters, OpenTelemetry, Prometheus/Grafana, image scanning, secured Kubernetes deployment, secrets management, restore testing, performance and fault-injection tests.

`.github/workflows/ci.yml` can be triggered manually on your **self-hosted Linux runner** when this directory is published as its own repository. This avoids assuming that private-repository hosted CI minutes are free. It does not execute untrusted pull requests automatically, deploy, publish images, or configure SonarQube credentials. Without a runner, execute the same Gradle commands locally. Pin reviewed container digests and scan the complete dependency/image graph before deployment; a successful local build is not a production security certification.
