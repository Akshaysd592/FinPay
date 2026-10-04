package dev.finpay.wallet.core;

/** Pure, deterministic policy. Amounts and balances are INR paise. */
public final class TransferRules {
    public static final long MAX_BALANCE = 100_000_000_000L;
    public static final long HIGH_RISK_THRESHOLD = 5_000_000L;

    private TransferRules() { }

    public static String rejection(long amount, long sourceBalance, long targetBalance) {
        if (amount <= 0) return "INVALID_AMOUNT";
        if (amount > HIGH_RISK_THRESHOLD) return "HIGH_RISK_AMOUNT";
        if (sourceBalance < amount) return "INSUFFICIENT_FUNDS";
        if (targetBalance > MAX_BALANCE - amount) return "BALANCE_LIMIT";
        return null;
    }

    public static long fundedBalance(long balance, long amount) {
        if (amount <= 0 || amount > HIGH_RISK_THRESHOLD || balance > MAX_BALANCE - amount) {
            throw new IllegalArgumentException("Funding exceeds sandbox amount or balance limit");
        }
        return Math.addExact(balance, amount);
    }
}
