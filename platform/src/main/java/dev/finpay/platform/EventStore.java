package dev.finpay.platform;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EventStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, PaymentEvent event) {
        // One event may be routed to multiple topics, each with its own outbox delivery ID.
        jdbc.update("INSERT INTO outbox(event_id, topic, partition_key, payload) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), topic, event.paymentId().toString(), json.writeValueAsString(event));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID eventId) {
        return jdbc.update("INSERT INTO inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) == 1;
    }

    public PaymentEvent decode(String payload) {
        return json.readValue(payload, PaymentEvent.class);
    }
}
