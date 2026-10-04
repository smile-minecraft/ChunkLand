package com.smile.chunkland.claim;

import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * {@link ClaimEconomy} for a server with purchases switched off
 * ({@code economy.enabled: false}).
 *
 * <p>Claims and expands are priced at zero by the caller and never reach
 * {@link #charge}, so no Economy provider has to be installed for them:
 * {@link #chargesClaims} is what tells the claim and expand sagas to skip the
 * provider and zero-price gates entirely.
 *
 * <p>Refunds are a different matter. Chunks bought while purchases were on
 * keep their durable cost basis, and switching purchases off must not turn
 * that money into nothing. The wrapper therefore reports the wrapped
 * provider's real availability instead of claiming one: the shrink and delete
 * sagas read the durable refund first and only then require a provider for a
 * positive amount, so a paid land fails closed before its domain commit while
 * a land that costs nothing still settles with no Economy installed.
 */
public final class PurchaseDisabledClaimEconomy implements ClaimEconomy {

    private final ClaimEconomy delegate;

    public PurchaseDisabledClaimEconomy(ClaimEconomy delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<ChargeResult> charge(UUID operationId, ClaimRequest request, Money price) {
        return delegate.charge(operationId, request, price);
    }

    @Override
    public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
        return delegate.refund(entry);
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public boolean chargesClaims() {
        return false;
    }
}
