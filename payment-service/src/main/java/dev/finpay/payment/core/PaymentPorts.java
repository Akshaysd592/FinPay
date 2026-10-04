package dev.finpay.payment.core;

import dev.finpay.platform.PaymentEvent;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PaymentPorts {
    private PaymentPorts() { }

    public record Request(@NotNull UUID sourceWalletId, @NotNull UUID targetWalletId,
            @Positive @Max(100_000_000) long amountMinor,
            @NotNull @Pattern(regexp = "INR") String currency,
            @NotBlank @Size(max = 200) String description,
            @NotNull @Pattern(regexp = "TRANSFER|MERCHANT") String purpose) { }

    public record Payment(UUID id, String ownerId, UUID sourceWalletId, UUID targetWalletId,
            long amountMinor, String currency, String description, String purpose, String status,
            String reason, Instant createdAt) { }

    public interface ManagePayments {
        Payment create(String ownerId, UUID key, Request request);
        Payment get(String ownerId, UUID id);
        List<Payment> history(String ownerId, int offset);
        void complete(PaymentEvent outcome);
    }

    public interface Persistence {
        Payment create(String ownerId, UUID key, Request request);
        Optional<Payment> find(String ownerId, UUID id);
        List<Payment> history(String ownerId, int offset);
        void complete(PaymentEvent outcome);
    }
}
