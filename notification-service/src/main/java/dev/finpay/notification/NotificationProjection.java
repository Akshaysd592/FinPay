package dev.finpay.notification;

import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationProjection {
    private final JdbcTemplate jdbc;
    private final EventStore events;

    public NotificationProjection(JdbcTemplate jdbc, EventStore events) { this.jdbc = jdbc; this.events = events; }

    @KafkaListener(topics = PaymentEvent.RESULTS)
    @Transactional
    public void project(String payload) {
        var event = events.decode(payload);
        if (!List.of("PAYMENT_SUCCEEDED", "PAYMENT_FAILED").contains(event.type())) {
            throw new IllegalArgumentException("Unsupported notification event");
        }
        if (!events.claim(event.eventId())) return;
        jdbc.update("INSERT INTO notifications(payment_id, owner_id, status) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                event.paymentId(), event.ownerId(), event.type());
    }

    @GetMapping
    public List<Map<String, Object>> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int offset) {
        if (offset < 0 || offset > 100_000) throw new IllegalArgumentException("Invalid offset");
        return jdbc.queryForList("SELECT payment_id, status, created_at FROM notifications WHERE owner_id = ? ORDER BY created_at DESC, payment_id LIMIT 50 OFFSET ?",
                jwt.getClaimAsString("preferred_username"), offset);
    }
}
