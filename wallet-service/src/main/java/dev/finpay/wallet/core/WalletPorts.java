package dev.finpay.wallet.core;

import dev.finpay.platform.PaymentEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class WalletPorts {
    private WalletPorts() { }

    public record Wallet(UUID id, String ownerId, String currency, long balanceMinor, long version) { }

    public interface ManageWallets {
        Wallet create(String ownerId);
        Wallet get(UUID id, String ownerId);
        void fund(UUID id, UUID operationId, long amountMinor);
        void transfer(PaymentEvent command);
    }

    public interface Persistence {
        Wallet create(String ownerId);
        Optional<Wallet> find(UUID id, String ownerId);
        List<Wallet> lock(UUID first, UUID second);
        boolean claim(UUID operationId, String fingerprint);
        void balance(UUID id, long amountMinor);
        void journal(PaymentEvent event);
        void publish(String topic, PaymentEvent event);
    }
}
