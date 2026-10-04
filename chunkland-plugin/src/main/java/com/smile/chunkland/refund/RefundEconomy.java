package com.smile.chunkland.refund;

import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Economy seam for the refund saga: a deposit that runs strictly after the
 * durable domain commit and strictly outside any SQL transaction.
 *
 * <p>The saga carries the ledger operation id as the idempotency key; the
 * entry's {@code priceMinorUnits} is the refund amount computed from the
 * durable cost basis, so implementations can deposit exactly that value.
 * {@link RefundOutcome#REFUNDED} settles the row as compensated; any other
 * outcome (including {@code null}, a thrown exception, or a failed future)
 * quarantines it for operator reconciliation without resending, because the
 * attempt itself may have moved money and the provider cannot dedup.
 *
 * <p>Cross-restart exactly-once rests solely on the ledger ordering (the
 * execution intent is parked before the deposit runs, and a parked row is
 * never resent), never on retries at this seam: calling deposit twice for
 * the same parked row can double-credit.
 *
 * <p>Production wires the shared Vault refund adapter, whose provider gate,
 * idempotency cache, and outcome contract are reused unchanged; only the saga
 * ordering (domain commit before deposit) is refund-specific and lives in
 * {@link RefundSaga}.
 */
public interface RefundEconomy {

    CompletionStage<RefundOutcome> deposit(LedgerEntry entry);

    /** Provider identity recorded on the refund ledger row. */
    default String providerId() {
        return "unknown-economy";
    }

    /**
     * Whether the backing provider can move money right now. The saga checks
     * this before creating any ledger row so a missing provider fails closed
     * instead of leaving stranded rows.
     */
    default boolean isAvailable() {
        return true;
    }

    /** Deposit backed by an explicit function (useful in tests). */
    static RefundEconomy of(String providerId, boolean available,
            java.util.function.Function<LedgerEntry, CompletionStage<RefundOutcome>> deposits) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(deposits, "deposits");
        return new RefundEconomy() {
            @Override
            public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
                return deposits.apply(entry);
            }

            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }
        };
    }

    /** Economy that always fails closed (useful in tests). */
    static RefundEconomy unavailable(String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        return new RefundEconomy() {
            @Override
            public CompletionStage<RefundOutcome> deposit(LedgerEntry entry) {
                Objects.requireNonNull(entry, "entry");
                return CompletableFuture.completedFuture(RefundOutcome.FAILED);
            }

            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
