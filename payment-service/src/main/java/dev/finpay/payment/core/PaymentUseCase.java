package dev.finpay.payment.core;

import dev.finpay.platform.ApiExceptionHandler.NotFound;
import dev.finpay.platform.PaymentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentUseCase implements PaymentPorts.ManagePayments {
    private final PaymentPorts.Persistence persistence;

    public PaymentUseCase(PaymentPorts.Persistence persistence) { this.persistence = persistence; }

    public PaymentPorts.Payment create(String ownerId, UUID key, PaymentPorts.Request request) {
        if (request.sourceWalletId().equals(request.targetWalletId())) {
            throw new IllegalArgumentException("Source and destination wallets must differ");
        }
        return persistence.create(ownerId, key, request);
    }

    @Transactional(readOnly = true)
    public PaymentPorts.Payment get(String ownerId, UUID id) {
        return persistence.find(ownerId, id).orElseThrow(NotFound::new);
    }

    @Transactional(readOnly = true)
    public List<PaymentPorts.Payment> history(String ownerId, int offset) {
        if (offset < 0 || offset > 100_000) throw new IllegalArgumentException("Offset must be between 0 and 100000");
        return persistence.history(ownerId, offset);
    }

    public void complete(PaymentEvent outcome) {
        if (!List.of("PAYMENT_SUCCEEDED", "PAYMENT_FAILED").contains(outcome.type())) {
            throw new IllegalArgumentException("Invalid payment outcome");
        }
        persistence.complete(outcome);
    }
}
