package dev.finpay.payment.adapter;

import dev.finpay.payment.core.PaymentPorts;
import dev.finpay.platform.ApiExceptionHandler.Conflict;
import dev.finpay.platform.EventStore;
import dev.finpay.platform.Fingerprint;
import dev.finpay.platform.PaymentEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentPersistenceAdapter implements PaymentPorts.Persistence {
    private final JdbcTemplate jdbc;
    private final EventStore events;

    public PaymentPersistenceAdapter(JdbcTemplate jdbc, EventStore events) { this.jdbc = jdbc; this.events = events; }

    public PaymentPorts.Payment create(String ownerId, UUID key, PaymentPorts.Request request) {
        UUID id = UUID.randomUUID();
        String fingerprint = Fingerprint.of(request.sourceWalletId(), request.targetWalletId(),
                request.amountMinor(), request.currency(), request.description(), request.purpose());
        int inserted = jdbc.update("""
                INSERT INTO payments(id, owner_id, idempotency_key, fingerprint, source_wallet_id, target_wallet_id,
                amount_minor, currency, description, purpose, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """, id, ownerId, key, fingerprint, request.sourceWalletId(), request.targetWalletId(),
                request.amountMinor(), request.currency(), request.description(), request.purpose());
        String existing = jdbc.queryForObject("SELECT fingerprint FROM payments WHERE owner_id = ? AND idempotency_key = ?",
                String.class, ownerId, key);
        if (!fingerprint.equals(existing)) throw new Conflict("Idempotency key already used for another request");
        if (inserted == 1) {
            events.append(PaymentEvent.COMMANDS, new PaymentEvent(UUID.randomUUID(), "PAYMENT_REQUESTED", id, ownerId,
                    request.sourceWalletId(), request.targetWalletId(), request.amountMinor(), request.currency(),
                    request.description(), request.purpose(), Instant.now(), null));
        }
        return jdbc.queryForObject("SELECT * FROM payments WHERE owner_id = ? AND idempotency_key = ?", this::map, ownerId, key);
    }

    public Optional<PaymentPorts.Payment> find(String ownerId, UUID id) {
        return jdbc.query("SELECT * FROM payments WHERE owner_id = ? AND id = ?", this::map, ownerId, id).stream().findFirst();
    }

    public List<PaymentPorts.Payment> history(String ownerId, int offset) {
        return jdbc.query("SELECT * FROM payments WHERE owner_id = ? ORDER BY created_at DESC, id LIMIT 50 OFFSET ?",
                this::map, ownerId, offset);
    }

    public void complete(PaymentEvent outcome) {
        if (!events.claim(outcome.eventId())) return;
        // Unknown/corrupted results are quarantined instead of silently acknowledged.
        // Serialize terminal decisions even if conflicting results arrive on different deliveries.
        var payment = jdbc.query("SELECT * FROM payments WHERE owner_id = ? AND id = ? FOR UPDATE",
                this::map, outcome.ownerId(), outcome.paymentId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown payment outcome"));
        if (!payment.sourceWalletId().equals(outcome.sourceWalletId()) || !payment.targetWalletId().equals(outcome.targetWalletId())
                || payment.amountMinor() != outcome.amountMinor() || !payment.currency().equals(outcome.currency())) {
            throw new IllegalArgumentException("Outcome does not match original payment");
        }
        String status = "PAYMENT_SUCCEEDED".equals(outcome.type()) ? "SUCCESS" : "FAILED";
        if (!"PENDING".equals(payment.status()) && !status.equals(payment.status())) {
            throw new IllegalArgumentException("Conflicting terminal payment outcome");
        }
        jdbc.update("UPDATE payments SET status = ?, reason = ?, completed_at = now() WHERE id = ? AND status = 'PENDING'",
                status, outcome.reason(), outcome.paymentId());
    }

    private PaymentPorts.Payment map(ResultSet row, int index) throws SQLException {
        return new PaymentPorts.Payment(row.getObject("id", UUID.class), row.getString("owner_id"),
                row.getObject("source_wallet_id", UUID.class), row.getObject("target_wallet_id", UUID.class),
                row.getLong("amount_minor"), row.getString("currency"), row.getString("description"),
                row.getString("purpose"), row.getString("status"), row.getString("reason"), row.getTimestamp("created_at").toInstant());
    }
}
