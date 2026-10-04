package dev.finpay.platform;

import java.time.Instant;
import java.util.UUID;

/** Version-one wire contract. Amounts are integer INR paise, never floating point. */
public record PaymentEvent(UUID eventId, String type, UUID paymentId, String ownerId,
        UUID sourceWalletId, UUID targetWalletId, long amountMinor, String currency,
        String description, String purpose, Instant occurredAt, String reason) {
    public static final String COMMANDS = "finpay.payment-commands.v1";
    public static final String RESULTS = "finpay.wallet-results.v1";
    public static final String JOURNAL = "finpay.journal.v1";
    public static final UUID CLEARING_ACCOUNT = new UUID(0, 0);

    public PaymentEvent outcome(boolean succeeded, String failureReason) {
        return new PaymentEvent(UUID.randomUUID(), succeeded ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED",
                paymentId, ownerId, sourceWalletId, targetWalletId, amountMinor, currency,
                description, purpose, Instant.now(), failureReason);
    }
}
