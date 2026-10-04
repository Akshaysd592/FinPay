package dev.finpay.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = OutboxIntegrationTest.Configuration.class,
        properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realm",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"})
class OutboxIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired EventStore events;
    @Autowired OutboxPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void cleanDatabase() { jdbc.execute("TRUNCATE outbox, inbox"); }

    @Test
    void publish_committedOutbox_reachesKafkaAndRecordsAcknowledgement() throws Exception {
        // Given
        var event = event();
        new TransactionTemplate(manager).executeWithoutResult(status -> events.append(PaymentEvent.COMMANDS, event));

        // When
        publisher.publish();

        // Then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Long.class)).isEqualTo(1);
        var received = new ArrayList<PaymentEvent>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class))) {
            consumer.subscribe(List.of(PaymentEvent.COMMANDS));
            // Bound consumer assignment and delivery waits in slow CI runners.
            Instant deadline = Instant.now().plusSeconds(30);
            while (received.stream().noneMatch(delivered -> delivered.eventId().equals(event.eventId())) && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofSeconds(1)).forEach(record -> received.add(events.decode(record.value())));
            }
        }
        assertThat(received).contains(event);
    }

    @Test
    void append_rolledBackBusinessTransaction_doesNotLeaveEvent() {
        // Given
        var transaction = new TransactionTemplate(manager);

        // When
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            events.append(PaymentEvent.COMMANDS, event());
            throw new IllegalStateException("Simulated business failure");
        })).isInstanceOf(IllegalStateException.class);

        // Then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
    }

    private static PaymentEvent event() {
        return new PaymentEvent(UUID.randomUUID(), "PAYMENT_REQUESTED", UUID.randomUUID(), "alice", UUID.randomUUID(),
                UUID.randomUUID(), 100, "INR", "Test", "TRANSFER", Instant.now(), null);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({EventStore.class, OutboxPublisher.class, KafkaConfiguration.class})
    static class Configuration { }
}
