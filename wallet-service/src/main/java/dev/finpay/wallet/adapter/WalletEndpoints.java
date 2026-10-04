package dev.finpay.wallet.adapter;

import dev.finpay.platform.EventStore;
import dev.finpay.platform.PaymentEvent;
import dev.finpay.wallet.core.WalletPorts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallets")
public class WalletEndpoints {
    private final WalletPorts.ManageWallets wallets;
    private final EventStore events;

    public WalletEndpoints(WalletPorts.ManageWallets wallets, EventStore events) {
        this.wallets = wallets;
        this.events = events;
    }

    @PostMapping
    public WalletPorts.Wallet create(@AuthenticationPrincipal Jwt jwt) { return wallets.create(jwt.getClaimAsString("preferred_username")); }

    @GetMapping("/{id}")
    public WalletPorts.Wallet get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return wallets.get(id, jwt.getClaimAsString("preferred_username"));
    }

    @PostMapping("/{id}/sandbox-funding")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void fund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID operationId,
            @Valid @RequestBody Funding request) {
        wallets.fund(id, operationId, request.amountMinor());
    }

    @KafkaListener(topics = PaymentEvent.COMMANDS)
    public void acceptCommand(String payload) { wallets.transfer(events.decode(payload)); }

    public record Funding(@Positive @Max(5_000_000) long amountMinor) { }
}
