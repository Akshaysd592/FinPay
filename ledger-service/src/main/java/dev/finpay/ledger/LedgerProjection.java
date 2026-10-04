package dev.finpay.ledger;

import dev.finpay.platform.ApiExceptionHandler.NotFound;
import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Rebuildable read model. The wallet journal remains the authoritative posting source. */
@RestController
@RequestMapping("/api/ledger")
public class LedgerProjection {
    private final JdbcTemplate jdbc;
    private final EventStore events;

    public LedgerProjection(JdbcTemplate jdbc, EventStore events) { this.jdbc = jdbc; this.events = events; }

    @KafkaListener(topics = PaymentEvent.JOURNAL)
    @Transactional
    public void project(String payload) {
        var event = events.decode(payload);
        if (!List.of("PAYMENT_SUCCEEDED", "WALLET_FUNDED").contains(event.type())) {
            throw new IllegalArgumentException("Journal accepts only committed financial operations");
        }
        if (!events.claim(event.eventId())) return;
        jdbc.update("""
                INSERT INTO ledger_pairs(payment_id, debit_account, credit_account, amount_minor, currency, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.paymentId(), event.sourceWalletId(), event.targetWalletId(), event.amountMinor(),
                event.currency(), Timestamp.from(event.occurredAt()));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> entries(@PathVariable UUID paymentId) {
        var entries = jdbc.queryForList("""
                SELECT debit_account AS account_id, 'DEBIT' AS side, amount_minor, currency FROM ledger_pairs WHERE payment_id = ?
                UNION ALL
                SELECT credit_account AS account_id, 'CREDIT' AS side, amount_minor, currency FROM ledger_pairs WHERE payment_id = ?
                """, paymentId, paymentId);
        if (entries.isEmpty()) throw new NotFound();
        return entries;
    }
}
