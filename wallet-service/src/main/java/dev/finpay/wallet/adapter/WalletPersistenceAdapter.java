package dev.finpay.wallet.adapter;

import dev.finpay.platform.ApiExceptionHandler.Conflict;
import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import dev.finpay.wallet.core.WalletPorts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletPersistenceAdapter implements WalletPorts.Persistence {
    private final JdbcTemplate jdbc;
    private final EventStore events;

    public WalletPersistenceAdapter(JdbcTemplate jdbc, EventStore events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public WalletPorts.Wallet create(String ownerId) {
        jdbc.update("INSERT INTO wallets(id, owner_id, currency) VALUES (?, ?, 'INR') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), ownerId);
        return jdbc.queryForObject("SELECT * FROM wallets WHERE owner_id = ?", this::map, ownerId);
    }

    public Optional<WalletPorts.Wallet> find(UUID id, String ownerId) {
        return jdbc.query("SELECT * FROM wallets WHERE id = ? AND owner_id = ?", this::map, id, ownerId).stream().findFirst();
    }

    public List<WalletPorts.Wallet> lock(UUID first, UUID second) {
        // All transfers acquire account locks in the same database UUID order, including opposing transfers.
        return jdbc.query("SELECT * FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE", this::map, first, second);
    }

    public boolean claim(UUID operationId, String fingerprint) {
        boolean inserted = jdbc.update("INSERT INTO wallet_operations(id, fingerprint) VALUES (?, ?) ON CONFLICT DO NOTHING",
                operationId, fingerprint) == 1;
        String existing = jdbc.queryForObject("SELECT fingerprint FROM wallet_operations WHERE id = ?", String.class, operationId);
        if (!fingerprint.equals(existing)) throw new Conflict("Operation ID already used for another request");
        return inserted;
    }

    public void balance(UUID id, long amountMinor) {
        jdbc.update("UPDATE wallets SET balance_minor = ?, version = version + 1 WHERE id = ?", amountMinor, id);
    }

    public void journal(PaymentEvent event) {
        jdbc.update("""
                INSERT INTO wallet_journal(payment_id, debit_account, credit_account, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?)
                """, event.paymentId(), event.sourceWalletId(), event.targetWalletId(), event.amountMinor(), event.currency());
    }

    public void publish(String topic, PaymentEvent event) { events.append(topic, event); }

    private WalletPorts.Wallet map(ResultSet row, int index) throws SQLException {
        return new WalletPorts.Wallet(row.getObject("id", UUID.class), row.getString("owner_id"),
                row.getString("currency"), row.getLong("balance_minor"), row.getLong("version"));
    }
}
