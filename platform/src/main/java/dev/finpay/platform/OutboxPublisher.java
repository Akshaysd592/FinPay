package dev.finpay.platform;

import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${finpay.outbox-delay-ms:1000}")
    @Transactional(rollbackFor = Exception.class)
    public void publish() throws Exception {
        // Small batches bound lock duration; SKIP LOCKED permits multiple service replicas.
        var pending = jdbc.queryForList("""
                SELECT event_id, topic, partition_key, payload FROM outbox
                WHERE published_at IS NULL ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED
                """);
        for (var row : pending) {
            try {
                // Mark delivered only after broker acknowledgement. A crash can cause replay, not loss.
                kafka.send((String) row.get("topic"), (String) row.get("partition_key"),
                        (String) row.get("payload")).get(12, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            jdbc.update("UPDATE outbox SET published_at = now() WHERE event_id = ?", row.get("event_id"));
        }
    }
}

