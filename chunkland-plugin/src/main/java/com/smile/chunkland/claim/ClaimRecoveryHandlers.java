package com.smile.chunkland.claim;

import com.smile.chunkland.persistence.CrashRecoveryScanner;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PaymentLookup;
import com.smile.chunkland.persistence.RecoveryHandlers;
import com.smile.chunkland.persistence.RecoveryResult;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Production assembly for startup crash recovery of claim operations.
 *
 * <p>This is the single production seam the startup caller uses to build
 * recovery handlers; tests exercise this factory instead of hand-assembling
 * handler method references. The caller owns the lifecycle: it supplies an
 * already-open ledger, the claim Economy integration, and a rebuilder that
 * reads the authoritative land repository and publishes into the shared
 * runtime store. Scanning itself runs through {@link CrashRecoveryScanner},
 * which never constructs Economy or runtime integrations.
 *
 * <p>Handler contract, per row state:
 * <ul>
 *   <li>{@code PAYMENT_PENDING}: no provider payment query exists, so the
 *       lookup stays unknown and the row is retained for reconciliation
 *       instead of being auto-charged or auto-failed.</li>
 *   <li>{@code CHARGED}: domain truth was never committed, so replay is
 *       unavailable and the row follows the compensation path with exactly
 *       one refund through the claim Economy.</li>
 *   <li>{@code DOMAIN_COMMITTED}: the runtime is rebuilt from the
 *       authoritative database and the row advances to {@code ACTIVE}. A
 *       missing or failing rebuild leaves the durable row untouched; it is
 *       never marked active and never refunded.</li>
 * </ul>
 *
 * <p>Remaining startup responsibility, intentionally left to the lifecycle
 * owner: opening and closing the persistence store, providing the Economy
 * bridge, sharing one registry store between this rebuilder and the
 * protection engine, and running the scan off any region thread.
 */
public final class ClaimRecoveryHandlers {

    private ClaimRecoveryHandlers() {
    }

    /**
     * Build the production recovery handlers for startup.
     *
     * @param economy the claim Economy integration used for compensation refunds
     * @param rebuilder the authoritative runtime rebuilder used for
     *        {@code DOMAIN_COMMITTED} rows
     * @return handlers wired to the given production integrations
     */
    public static RecoveryHandlers forStartup(ClaimEconomy economy, RuntimeRegistryRebuilder rebuilder) {
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(rebuilder, "rebuilder");
        return RecoveryHandlers.of(
                entry -> CompletableFuture.completedFuture(PaymentLookup.unknown()),
                economy::refund,
                (entry, payload) -> CompletableFuture.failedFuture(
                        new IllegalStateException("claim replay is not configured for startup recovery")),
                rebuilder::rebuildForRecovery);
    }

    /**
     * Run the startup scan with production handlers.
     *
     * @param ledger the already-open operation ledger
     * @param economy the claim Economy integration used for compensation refunds
     * @param rebuilder the authoritative runtime rebuilder used for
     *        {@code DOMAIN_COMMITTED} rows
     * @return one result per scanned row, in ledger order
     */
    public static CompletionStage<List<RecoveryResult>> scanAtStartup(
            OperationLedger ledger, ClaimEconomy economy, RuntimeRegistryRebuilder rebuilder) {
        Objects.requireNonNull(ledger, "ledger");
        return CrashRecoveryScanner.scanAtStartup(ledger, forStartup(economy, rebuilder));
    }
}
