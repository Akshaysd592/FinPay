package dev.finpay.platform;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DeliveryUnitTest {
    @Mock JdbcTemplate jdbc;
    @Mock KafkaTemplate<String, String> kafka;

    @Test
    void publish_brokerAcknowledges_marksDeliveredAfterSend() throws Exception {
        // Given
        UUID eventId = UUID.randomUUID();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row(eventId)));
        when(kafka.send("topic", "key", "payload")).thenReturn(CompletableFuture.completedFuture(null));

        // When
        new OutboxPublisher(jdbc, kafka).publish();

        // Then
        var order = inOrder(kafka, jdbc);
        order.verify(kafka).send("topic", "key", "payload");
        order.verify(jdbc).update("UPDATE outbox SET published_at = now() WHERE event_id = ?", eventId);
    }

    @Test
    void publish_brokerFails_doesNotMarkDelivered() {
        // Given
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row(UUID.randomUUID())));
        when(kafka.send("topic", "key", "payload")).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));

        // When
        var failure = catchThrowable(() -> new OutboxPublisher(jdbc, kafka).publish());

        // Then
        assertThat(failure).isInstanceOf(ExecutionException.class);
        verify(jdbc, never()).update(anyString(), any(UUID.class));
    }

    @Test
    void fingerprint_ambiguousDelimiters_producesDifferentIdentity() {
        // Given
        String first = Fingerprint.of("a:b", "c");

        // When
        String second = Fingerprint.of("a", "b:c");

        // Then
        assertThat(second).isNotEqualTo(first);
        assertThat(Fingerprint.of("a:b", "c")).isEqualTo(first);
    }

    private static Map<String, Object> row(UUID eventId) {
        return Map.of("event_id", eventId, "topic", "topic", "partition_key", "key", "payload", "payload");
    }
}
