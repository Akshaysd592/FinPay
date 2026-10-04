package dev.finpay.wallet;

import dev.finpay.platform.OutboxPublisher;
import dev.finpay.platform.PaymentEvent;
import dev.finpay.wallet.core.WalletPorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
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
@SpringBootTest(properties = {"finpay.sandbox-funding=true", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "spring.kafka.bootstrap-servers=localhost:1",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realm",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"})
class WalletIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }

    @MockitoBean OutboxPublisher publisher;
    @Autowired WalletPorts.ManageWallets wallets;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() { jdbc.execute("TRUNCATE wallet_journal, wallet_operations, wallets, outbox, inbox CASCADE"); }

    @Test
    void transfer_duplicateCommand_movesFundsAndPostsJournalOnce() {
        // Given
        var source = wallets.create("alice");
        var target = wallets.create("bob");
        wallets.fund(source.id(), UUID.randomUUID(), 1000);
        var command = command(source.id(), target.id(), "alice", 400);

        // When
        wallets.transfer(command);
        wallets.transfer(command);

        // Then
        assertThat(wallets.get(source.id(), "alice").balanceMinor()).isEqualTo(600);
        assertThat(wallets.get(target.id(), "bob").balanceMinor()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_journal WHERE payment_id = ?", Long.class, command.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE topic = ?", Long.class, PaymentEvent.RESULTS)).isEqualTo(1);
    }

    @Test
    void transfer_concurrentDebits_neverOverspends() throws Exception {
        // Given
        var source = wallets.create("alice");
        var target = wallets.create("bob");
        wallets.fund(source.id(), UUID.randomUUID(), 1000);
        var tasks = new ArrayList<Callable<Void>>();
        for (int index = 0; index < 8; index++) {
            var command = command(source.id(), target.id(), "alice", 200);
            tasks.add(() -> { wallets.transfer(command); return null; });
        }

        // When
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(tasks)) future.get();
        }

        // Then
        assertThat(wallets.get(source.id(), "alice").balanceMinor()).isZero();
        assertThat(wallets.get(target.id(), "bob").balanceMinor()).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_journal", Long.class)).isEqualTo(6);
    }

    @Test
    void transfer_wrongOwner_rejectsWithoutBalanceChange() {
        // Given
        var source = wallets.create("alice");
        var target = wallets.create("bob");
        wallets.fund(source.id(), UUID.randomUUID(), 1000);

        // When
        wallets.transfer(command(source.id(), target.id(), "mallory", 400));

        // Then
        assertThat(wallets.get(source.id(), "alice").balanceMinor()).isEqualTo(1000);
        assertThat(wallets.get(target.id(), "bob").balanceMinor()).isZero();
    }

    @Test
    void transfer_databaseWriteFailure_rollsBackBalancesAndClaim() {
        // Given
        var source = wallets.create("alice");
        var target = wallets.create("bob");
        wallets.fund(source.id(), UUID.randomUUID(), 1000);
        var command = command(source.id(), target.id(), "alice", 400);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT simulate_failure CHECK (topic <> 'finpay.wallet-results.v1')");

        // When / Then
        try {
            assertThatThrownBy(() -> wallets.transfer(command)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(wallets.get(source.id(), "alice").balanceMinor()).isEqualTo(1000);
            assertThat(wallets.get(target.id(), "bob").balanceMinor()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_operations WHERE id = ?", Long.class, command.paymentId())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT simulate_failure");
        }
    }

    @Test
    void journal_updateAttempt_isRejectedByDatabase() {
        // Given
        var source = wallets.create("alice");
        wallets.fund(source.id(), UUID.randomUUID(), 1000);

        // When / Then
        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_journal SET amount_minor = 1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private static PaymentEvent command(UUID source, UUID target, String owner, long amount) {
        return new PaymentEvent(UUID.randomUUID(), "PAYMENT_REQUESTED", UUID.randomUUID(), owner,
                source, target, amount, "INR", "SWIGGY", "MERCHANT", Instant.now(), null);
    }
}
