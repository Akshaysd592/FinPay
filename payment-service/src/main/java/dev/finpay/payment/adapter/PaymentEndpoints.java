package dev.finpay.payment.adapter;

import dev.finpay.payment.core.PaymentPorts;
import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentEndpoints {
    private final PaymentPorts.ManagePayments payments;
    private final EventStore events;

    public PaymentEndpoints(PaymentPorts.ManagePayments payments, EventStore events) {
        this.payments = payments;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<PaymentPorts.Payment> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody PaymentPorts.Request request) {
        var payment = payments.create(jwt.getClaimAsString("preferred_username"), key, request);
        return ResponseEntity.accepted().location(URI.create("/api/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/{id}")
    public PaymentPorts.Payment get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return payments.get(jwt.getClaimAsString("preferred_username"), id);
    }

    @GetMapping
    public List<PaymentPorts.Payment> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int offset) {
        return payments.history(jwt.getClaimAsString("preferred_username"), offset);
    }

    @KafkaListener(topics = PaymentEvent.RESULTS)
    public void acceptOutcome(String payload) { payments.complete(events.decode(payload)); }
}
