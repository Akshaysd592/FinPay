package dev.finpay.intelligence;

import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights")
public class SpendingProjection {
    private final JdbcTemplate jdbc;
    private final EventStore events;
    private final InsightNarrator narrator;

    public SpendingProjection(JdbcTemplate jdbc, EventStore events, InsightNarrator narrator) {
        this.jdbc = jdbc;
        this.events = events;
        this.narrator = narrator;
    }

    @KafkaListener(topics = PaymentEvent.JOURNAL)
    @Transactional
    public void project(String payload) {
        var event = events.decode(payload);
        if ("WALLET_FUNDED".equals(event.type())) return;
        if (!"PAYMENT_SUCCEEDED".equals(event.type())) throw new IllegalArgumentException("Unsupported journal event");
        if (!events.claim(event.eventId())) return;
        jdbc.update("""
                INSERT INTO spending(payment_id, owner_id, amount_minor, currency, category, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, event.paymentId(), event.ownerId(), event.amountMinor(), event.currency(),
                CategoryRules.categorize(event.description()), Timestamp.from(event.occurredAt()));
    }

    @GetMapping("/{month}")
    public Report report(@PathVariable String month, @AuthenticationPrincipal Jwt jwt) {
        YearMonth period = parseMonth(month);
        var totals = jdbc.query("""
                SELECT category, sum(amount_minor) AS total FROM spending
                WHERE owner_id = ? AND occurred_at >= ? AND occurred_at < ?
                GROUP BY category ORDER BY category
                """, (row, index) -> new CategoryTotal(row.getString("category"), row.getBigDecimal("total")),
                jwt.getClaimAsString("preferred_username"), Timestamp.from(period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(period.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return new Report(period.toString(), "INR", totals, "RULE_BASED", "EVENTUALLY_CONSISTENT");
    }

    @PostMapping("/{month}/summary")
    public InsightNarrator.Summary summarize(@PathVariable String month, @AuthenticationPrincipal Jwt jwt) {
        // The only tool available to the narrator is this authenticated, backend-calculated report.
        return narrator.summarize(report(month, jwt));
    }

    private static YearMonth parseMonth(String month) {
        try {
            var period = YearMonth.parse(month);
            if (period.getYear() < 2000 || period.getYear() > 2100) throw new IllegalArgumentException("Unsupported year");
            return period;
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Month must use YYYY-MM format", exception);
        }
    }

    public record CategoryTotal(String category, BigDecimal amountMinor) { }
    public record Report(String month, String currency, List<CategoryTotal> categories, String classification, String consistency) { }
}
