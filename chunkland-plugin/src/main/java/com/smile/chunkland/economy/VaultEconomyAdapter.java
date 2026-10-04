package com.smile.chunkland.economy;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.mutation.EconomyOperator;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Vault-backed {@link EconomyOperator} with explicit capabilities, single
 * double boundary, and idempotent retry semantics.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Declare {@link EconomyCapabilities}: when {@link VaultBridge} is absent
 *       the adapter reports not available / not chargeable (fail-closed).</li>
 *   <li>Convert {@link Money} ↔ Vault {@code double} exclusively via
 *       {@link VaultMoneyConverter} at the boundary.</li>
 *   <li>Carry {@code operationId} through every charge and refund call and
 *       coalesce concurrent in-process callers: calls sharing the same
 *       {@code operationId} observe a single bridge invocation and share the
 *       same outcome, so retry storms and concurrent claim submissions cannot
 *       double-charge or double-credit within one process lifetime. This
 *       cache is process-local and is never a cross-restart guarantee:
 *       exactly-once across restarts rests solely on the ledger ordering
 *       (park the execution intent before depositing, never resend a parked
 *       row), because the bridge discards the idempotency key and the
 *       provider cannot dedup.</li>
 *   <li>Scope the idempotency key handed to the bridge by operation kind
 *       ({@code charge:<uuid>} for withdraw, {@code refund:<uuid>} for
 *       deposit) so a charge and a refund sharing the same underlying UUID
 *       cannot collapse in any provider-side global dedup.</li>
 *   <li>On refund, compare the recorded {@code economyProviderId} against the
 *       bridge's current provider identity; mismatch must surface as
 *       {@link RefundOutcome#UNKNOWN} without calling deposit, so callers can
 *       drive reconciliation instead of delegating funds to a different
 *       economy plugin.</li>
 *   <li>Refund retry lifecycle within one process: keep successful
 *       {@link RefundOutcome#REFUNDED} outcomes cached so repeated calls
 *       never double-deposit, but evict non-success outcomes
 *       ({@link RefundOutcome#UNKNOWN}, {@link RefundOutcome#FAILED})
 *       atomically on completion. A transient bridge failure or temporary
 *       provider mismatch must not permanently block retries: once the
 *       underlying condition clears the next call re-runs the
 *       provider/bridge check and may succeed. The atomic in-flight dedup
 *       invariant (one bridge call per concurrent window) is preserved
 *       across the eviction/re-entry boundary. None of this survives a
 *       restart; callers above (saga, recovery scanner) are responsible for
 *       never calling twice for the same parked row.</li>
 *   <li>Never run inside a SQL transaction; the coordinator guarantees this
 *       ordering (Ledger → Economy → DomainCommit).</li>
 * </ul>
 */
public final class VaultEconomyAdapter implements EconomyOperator {

    /**
     * Prefix used to derive the idempotency key handed to {@link VaultBridge#withdraw}
     * from the raw operation UUID. Distinct from the refund prefix so a charge
     * and a refund sharing the same raw UUID cannot collide in any global
     * provider-side dedup that hashes UUID bytes.
     */
    static final String CHARGE_KEY_PREFIX = "charge:";

    /**
     * Prefix used to derive the idempotency key handed to {@link VaultBridge#deposit}
     * from the raw operation UUID. See {@link #CHARGE_KEY_PREFIX} for the
     * scoping rationale.
     */
    static final String REFUND_KEY_PREFIX = "refund:";

    private final VaultBridge bridge;
    private final Currency currency;
    private final Function<MutationRequest, Money> priceResolver;
    /**
     * Per-operationId in-flight or completed result. A placeholder future is
     * published before provider work starts, allowing concurrent callers to
     * share the same in-flight operation without holding a map-bin lock during
     * bridge I/O.
     */
    private final ConcurrentHashMap<UUID, CompletableFuture<EconomyResult>> chargeFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<RefundOutcome>> refundFutures = new ConcurrentHashMap<>();

    public VaultEconomyAdapter(VaultBridge bridge, Currency currency,
                               Function<MutationRequest, Money> priceResolver) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.priceResolver = Objects.requireNonNull(priceResolver, "priceResolver");
    }

    /** Convenience: fixed price for all requests (useful in tests). */
    public static VaultEconomyAdapter withFixedPrice(VaultBridge bridge, Currency currency, Money fixedPrice) {
        Objects.requireNonNull(fixedPrice, "fixedPrice");
        return new VaultEconomyAdapter(bridge, currency, ignored -> fixedPrice);
    }

    public EconomyCapabilities capabilities() {
        if (!bridge.isAvailable()) {
            return EconomyCapabilities.unavailable();
        }
        return EconomyCapabilities.available(bridge.providerId(), bridge.providerId());
    }

    @Override
    public CompletionStage<EconomyResult> charge(MutationRequest request, UUID operationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(operationId, "operationId");

        CompletableFuture<EconomyResult> candidate = new CompletableFuture<>();
        CompletableFuture<EconomyResult> existing = chargeFutures.putIfAbsent(operationId, candidate);
        if (existing != null) {
            return existing.minimalCompletionStage();
        }

        // Publish first, then perform provider work outside the map operation.
        // The successful or failed result remains cached with the existing
        // charge idempotency semantics.
        try {
            candidate.complete(performCharge(request, operationId));
        } catch (Throwable t) {
            candidate.completeExceptionally(t);
        }
        return candidate.minimalCompletionStage();
    }

    private EconomyResult performCharge(MutationRequest request, UUID operationId) {
        // Fail-closed when provider absent
        if (!bridge.isAvailable() || !capabilities().canCharge()) {
            return EconomyResult.failed("economy.unavailable");
        }

        Money price;
        try {
            price = priceResolver.apply(request);
        } catch (Exception e) {
            return EconomyResult.failed("economy.failed");
        }
        if (price == null) {
            return EconomyResult.failed("economy.failed");
        }
        if (!price.currency().equals(currency)) {
            return EconomyResult.failed("economy.failed");
        }
        if (price.isNegative()) {
            return EconomyResult.failed("economy.failed");
        }
        if (price.isZero()) {
            return EconomyResult.ok();
        }

        double amount;
        try {
            amount = VaultMoneyConverter.toVaultAmount(price);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return EconomyResult.failed("economy.failed");
        }
        if (!Double.isFinite(amount) || Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            return EconomyResult.failed("economy.failed");
        }

        UUID playerId = extractPlayerId(request);
        if (playerId == null) {
            return EconomyResult.failed("economy.failed");
        }

        VaultBridge.Response response;
        try {
            response = bridge.withdraw(playerId, amount, scopedId(CHARGE_KEY_PREFIX, operationId));
        } catch (Exception e) {
            return EconomyResult.failed("economy.failed");
        }
        if (response == null) {
            return EconomyResult.failed("economy.failed");
        }
        return response.success()
                ? EconomyResult.ok()
                : EconomyResult.failed(response.errorKey() != null ? response.errorKey() : "economy.failed");
    }

    /**
     * Refund seam for {@link com.smile.chunkland.persistence.RecoveryHandlers}.
     *
     * <p>Result cache, process-local only:
     * <ul>
     *   <li>Concurrent calls sharing the same {@code operationId} observe
     *       at most one bridge deposit per in-flight window: a second caller
     *       that arrives while the first is still running shares the same
     *       future, which is published before the provider work starts, so
     *       the map is never locked while bridge I/O is running.</li>
     *   <li>A successful {@link RefundOutcome#REFUNDED} is cached
     *       for the operationId while this process lives, so repeated
     *       compensation calls in the same process cannot double-deposit.
     *       The cache does not survive a restart; cross-restart exactly-once
     *       is the ledger's job (a parked row is never resent), because the
     *       bridge discards the idempotency key and the provider cannot
     *       dedup.</li>
     *   <li>A non-success outcome ({@link RefundOutcome#UNKNOWN},
     *       {@link RefundOutcome#FAILED}) is evicted from the cache via an
     *       atomic compare-and-remove as soon as the in-flight attempt
     *       completes. A transient bridge failure or a temporary provider
     *       mismatch must not permanently block retries: when the underlying
     *       condition clears, the next call performs a fresh bridge check
     *       and may succeed. The eviction uses compare-and-remove so a
     *       concurrent retry that has just populated a fresh
     *       {@code REFUNDED} entry is never clobbered.</li>
     * </ul>
     */
    public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        UUID operationId = entry.operationId();

        while (true) {
            CompletableFuture<RefundOutcome> candidate = new CompletableFuture<>();
            CompletableFuture<RefundOutcome> existing = refundFutures.putIfAbsent(operationId, candidate);
            if (existing != null) {
                if (!existing.isDone()) {
                    // Another caller owns the in-flight operation.
                    return existing.minimalCompletionStage();
                }
                if (completedRefundOutcome(existing) == RefundOutcome.REFUNDED) {
                    // Permanent success cache: a prior call already
                    // deposited. Retries must not deposit again.
                    return existing.minimalCompletionStage();
                }

                // A completed non-success is retryable. Compare-and-remove
                // prevents this caller from deleting a newer in-flight or
                // successful entry before trying to claim the next window.
                refundFutures.remove(operationId, existing);
                continue;
            }

            // The placeholder is visible before any bridge call. Provider work
            // therefore runs outside all ConcurrentHashMap update operations.
            try {
                candidate.complete(performRefund(entry));
            } catch (Throwable t) {
                candidate.completeExceptionally(t);
            }

            // Keep only a successful refund. Compare-and-remove preserves a
            // replacement retry that won the race after this result completed.
            if (completedRefundOutcome(candidate) != RefundOutcome.REFUNDED) {
                refundFutures.remove(operationId, candidate);
            }
            return candidate.minimalCompletionStage();
        }
    }

    private static RefundOutcome completedRefundOutcome(CompletableFuture<RefundOutcome> future) {
        try {
            return future.getNow(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private RefundOutcome performRefund(LedgerEntry entry) {
        UUID operationId = entry.operationId();

        if (!bridge.isAvailable() || !capabilities().canRefund()) {
            return RefundOutcome.FAILED;
        }

        // Provider-identity gate: if the ledger entry was charged against a
        // different economy provider than the one currently backing the
        // bridge, do NOT deposit into the new provider. The outcome is
        // UNKNOWN so callers (RecoveryHandlers) can drive reconciliation
        // instead of trusting a blind refund.
        String recorded = entry.economyProviderId();
        String current = bridge.providerId();
        if (recorded == null || current == null || !recorded.equals(current)) {
            return RefundOutcome.UNKNOWN;
        }

        Long priceMinor = entry.priceMinorUnits();
        if (priceMinor == null || priceMinor < 0) {
            return RefundOutcome.FAILED;
        }
        if (priceMinor == 0) {
            return RefundOutcome.REFUNDED;
        }

        Money amount;
        try {
            amount = new Money(priceMinor, currency);
        } catch (Exception e) {
            return RefundOutcome.FAILED;
        }

        double vaultAmount;
        try {
            vaultAmount = VaultMoneyConverter.toVaultAmount(amount);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return RefundOutcome.FAILED;
        }
        if (!Double.isFinite(vaultAmount) || vaultAmount < 0) {
            return RefundOutcome.FAILED;
        }

        UUID playerId = entry.actor();
        if (playerId == null) {
            return RefundOutcome.FAILED;
        }

        VaultBridge.Response response;
        try {
            response = bridge.deposit(playerId, vaultAmount, scopedId(REFUND_KEY_PREFIX, operationId));
        } catch (Exception e) {
            return RefundOutcome.UNKNOWN;
        }
        if (response == null) {
            return RefundOutcome.UNKNOWN;
        }
        return response.success() ? RefundOutcome.REFUNDED : RefundOutcome.FAILED;
    }

    /**
     * Build a deterministic, namespace-scoped idempotency key for the
     * bridge. The raw operationId is never passed to the bridge so that a
     * charge and a refund sharing the same underlying UUID cannot collide
     * in any provider-side global dedup. UUID v3 (name-based) over the
     * prefix + raw UUID bytes gives a stable, type-3 UUID the bridge can
     * consume without exposing the raw operationId.
     */
    static UUID scopedId(String prefix, UUID operationId) {
        return UUID.nameUUIDFromBytes((prefix + operationId.toString()).getBytes(StandardCharsets.UTF_8));
    }

    /** Expose charge cache size for tests (do not use in production). */
    int chargeCacheSizeForTest() {
        return chargeFutures.size();
    }

    int refundCacheSizeForTest() {
        return refundFutures.size();
    }

    private static UUID extractPlayerId(MutationRequest request) {
        if (request.requestedBy() == null) return null;
        if (request.requestedBy() instanceof com.smile.chunkland.api.land.OwnerRef.PlayerOwnerRef p) {
            return p.uuid();
        }
        return null;
    }
}
