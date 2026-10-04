package dev.finpay.payment;

import dev.finpay.payment.core.PaymentPorts;
import dev.finpay.platform.ApiExceptionHandler.Conflict;
import dev.finpay.platform.ApiExceptionHandler.NotFound;
import dev.finpay.platform.EventStore;
import dev.finpay.platform.OutboxPublisher;
import dev.finpay.platform.PaymentEvent;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:1", "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"})
class PaymentIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @MockitoBean OutboxPublisher publisher;
    @Autowired PaymentPorts.ManagePayments payments;
    @Autowired EventStore events;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() { jdbc.execute("TRUNCATE payments, outbox, inbox"); }

    @Test
    void create_concurrentIdenticalKeys_persistsOnePaymentAndCommand() throws Exception {
        // Given
        UUID key = UUID.randomUUID();
        var request = request(100);
        Callable<PaymentPorts.Payment> task = () -> payments.create("alice", key, request);

        // When
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = executor.invokeAll(List.of(task, task));

            // Then
            assertThat(attempts.getFirst().get().id()).isEqualTo(attempts.getLast().get().id());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(1);
        }
    }

    @Test
    void create_changedPayloadWithSameKey_rejectsConflict() {
        // Given
        UUID key = UUID.randomUUID();
        var request = request(100);
        payments.create("alice", key, request);
        var changed = new PaymentPorts.Request(request.sourceWalletId(), request.targetWalletId(), 101, "INR", "SWIGGY", "MERCHANT");

        // When / Then
        assertThatThrownBy(() -> payments.create("alice", key, changed)).isInstanceOf(Conflict.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void get_differentOwner_hidesPayment() {
        // Given
        var payment = payments.create("alice", UUID.randomUUID(), request(100));

        // When / Then
        assertThatThrownBy(() -> payments.get("bob", payment.id())).isInstanceOf(NotFound.class);
    }

    @Test
    void complete_duplicateAndContradictoryResults_preservesTerminalOutcome() {
        // Given
        var payment = payments.create("alice", UUID.randomUUID(), request(100));
        PaymentEvent command = events.decode(jdbc.queryForObject("SELECT payload FROM outbox", String.class));
        var success = command.outcome(true, null);

        // When
        payments.complete(success);
        payments.complete(success);

        // Then
        assertThat(payments.get("alice", payment.id()).status()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> payments.complete(command.outcome(false, "INSUFFICIENT_FUNDS")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting terminal payment outcome");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox", Long.class)).isEqualTo(1);
    }

    private static PaymentPorts.Request request(long amount) {
        return new PaymentPorts.Request(UUID.randomUUID(), UUID.randomUUID(), amount, "INR", "SWIGGY", "MERCHANT");
    }
}
