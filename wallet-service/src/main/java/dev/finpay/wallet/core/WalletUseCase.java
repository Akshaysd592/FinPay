package dev.finpay.wallet.core;

import dev.finpay.platform.ApiExceptionHandler.NotFound;
import dev.finpay.platform.Fingerprint;
import dev.finpay.platform.PaymentEvent;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WalletUseCase implements WalletPorts.ManageWallets {
    private final WalletPorts.Persistence persistence;
    private final boolean sandboxFunding;

    public WalletUseCase(WalletPorts.Persistence persistence,
            @Value("${finpay.sandbox-funding:false}") boolean sandboxFunding) {
        this.persistence = persistence;
        this.sandboxFunding = sandboxFunding;
    }

    @Override
    public WalletPorts.Wallet create(String ownerId) { return persistence.create(ownerId); }

    @Override
    @Transactional(readOnly = true)
    public WalletPorts.Wallet get(UUID id, String ownerId) {
        return persistence.find(id, ownerId).orElseThrow(NotFound::new);
    }

    @Override
    public void fund(UUID id, UUID operationId, long amountMinor) {
        if (!sandboxFunding) throw new IllegalArgumentException("Sandbox funding is disabled");
        if (!persistence.claim(operationId, Fingerprint.of("FUND", id, amountMinor))) return;
        var wallets = persistence.lock(id, id);
        if (wallets.isEmpty()) throw new NotFound();
        var wallet = wallets.getFirst();
        long funded = TransferRules.fundedBalance(wallet.balanceMinor(), amountMinor);
        var event = new PaymentEvent(UUID.randomUUID(), "WALLET_FUNDED", operationId, wallet.ownerId(),
                PaymentEvent.CLEARING_ACCOUNT, id, amountMinor, "INR", "Sandbox funds", "FUNDING", Instant.now(), null);
        persistence.balance(id, funded);
        persistence.journal(event);
        persistence.publish(PaymentEvent.JOURNAL, event);
    }

    @Override
    public void transfer(PaymentEvent command) {
        validateCommand(command);
        if (!persistence.claim(command.paymentId(), Fingerprint.of(command.ownerId(), command.sourceWalletId(),
                command.targetWalletId(), command.amountMinor(), command.currency(), command.description(), command.purpose()))) return;
        var wallets = persistence.lock(command.sourceWalletId(), command.targetWalletId());
        var source = wallets.stream().filter(wallet -> wallet.id().equals(command.sourceWalletId())).findFirst();
        var target = wallets.stream().filter(wallet -> wallet.id().equals(command.targetWalletId())).findFirst();
        String rejection = "WALLET_UNAVAILABLE";
        if (source.isPresent() && target.isPresent() && source.get().ownerId().equals(command.ownerId())) {
            rejection = TransferRules.rejection(command.amountMinor(), source.get().balanceMinor(), target.get().balanceMinor());
        }
        var outcome = command.outcome(rejection == null, rejection);
        if (rejection == null) {
            persistence.balance(command.sourceWalletId(), source.orElseThrow().balanceMinor() - command.amountMinor());
            persistence.balance(command.targetWalletId(), target.orElseThrow().balanceMinor() + command.amountMinor());
            persistence.journal(outcome);
            persistence.publish(PaymentEvent.JOURNAL, outcome);
        }
        persistence.publish(PaymentEvent.RESULTS, outcome);
    }

    private static void validateCommand(PaymentEvent command) {
        if (!"PAYMENT_REQUESTED".equals(command.type()) || command.paymentId() == null
                || command.sourceWalletId() == null || command.targetWalletId() == null
                || command.sourceWalletId().equals(command.targetWalletId())
                || command.ownerId() == null || command.ownerId().isBlank()
                || !"INR".equals(command.currency())) {
            throw new IllegalArgumentException("Invalid payment command");
        }
    }
}
