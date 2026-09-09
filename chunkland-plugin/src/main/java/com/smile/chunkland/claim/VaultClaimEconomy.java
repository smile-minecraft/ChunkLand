package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.economy.VaultEconomyAdapter;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Production {@link ClaimEconomy} over the shared Vault bridge.
 *
 * <p>Charge reuses the established adapter contract exactly: per-call fixed
 * price, scoped operation-id idempotency, the explicit zero-value shortcut, and
 * the provider identity gate. A per-call adapter instance is safe for charge
 * because the saga issues exactly one charge per operation id and never retries
 * it inline. Refund keeps one shared adapter instance so concurrent retries for
 * the same operation share the success cache and can never double-deposit.
 */
public final class VaultClaimEconomy implements ClaimEconomy {

    private final VaultBridge bridge;
    private final com.smile.chunkland.api.money.Currency currency;
    private final VaultEconomyAdapter sharedRefundAdapter;

    public VaultClaimEconomy(VaultBridge bridge, com.smile.chunkland.api.money.Currency currency) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.sharedRefundAdapter = new VaultEconomyAdapter(bridge, currency, request -> {
            throw new IllegalStateException("refund adapter must never resolve a charge price");
        });
    }

    @Override
    public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(price, "price");
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, currency, price);
        var mutation = new com.smile.chunkland.api.mutation.MutationRequest(
                com.smile.chunkland.api.mutation.MutationKind.LAND_CREATE,
                null, request.owner(), request.chunks(), request.displayName());
        CompletionStage<com.smile.chunkland.runtime.mutation.EconomyOperator.EconomyResult> stage;
        try {
            stage = adapter.charge(mutation, operationId);
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("economy charge returned null"));
        }
        return stage.thenApply(result -> {
            if (result == null) {
                throw new IllegalStateException("economy charge completed with null");
            }
            if (result.success()) {
                return ChargeResult.ok();
            }
            return ChargeResult.failed(result.diagnosticKey() != null ? result.diagnosticKey() : "economy.failed");
        });
    }

    @Override
    public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return sharedRefundAdapter.refund(entry);
    }

    @Override
    public String providerId() {
        String id = bridge.providerId();
        return id != null ? id : "unknown-economy";
    }

    /** Owner extraction shared with the charge path: server land never charges. */
    static boolean isServerOwned(OwnerRef owner) {
        return owner instanceof OwnerRef.ServerOwnerRef;
    }
}
