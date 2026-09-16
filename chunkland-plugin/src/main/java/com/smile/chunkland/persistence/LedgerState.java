package com.smile.chunkland.persistence;

import java.util.Locale;
import java.util.Optional;

/** The persisted states of a durable operation. */
public enum LedgerState {
    CREATED,
    PAYMENT_PENDING,
    CHARGED,
    DOMAIN_COMMITTED,
    ACTIVE,
    COMPENSATION_PENDING,
    COMPENSATED,
    FAILED,
    NEEDS_RECONCILIATION,
    /**
     * Explicit operator verdicts over a {@code NEEDS_RECONCILIATION} row,
     * recorded by {@code /land admin ledger resolve}. Terminal and
     * recovery-inert: startup recovery never writes them, and no money,
     * domain replay or compensation follows from them. {@code REFUNDED} marks
     * that the operator handled the money outside the ledger — it is not an
     * automatic refund.
     */
    RESOLVED,
    REFUNDED,
    IGNORED;

    /** Parses the exact storage spelling; malformed values are rejected. */
    public static LedgerState parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ledger state must not be null or blank");
        }
        if (!value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("ledger state must be uppercase: " + value);
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown ledger state: " + value, failure);
        }
    }

    public static Optional<LedgerState> tryParse(String value) {
        try {
            return Optional.of(parse(value));
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    public boolean isTerminal() {
        return this == ACTIVE || this == FAILED || this == COMPENSATED || this == NEEDS_RECONCILIATION
                || this == RESOLVED || this == REFUNDED || this == IGNORED;
    }

    public RecoveryClassification recoveryClassification() {
        return switch (this) {
            case CREATED -> RecoveryClassification.FAIL_UNCHARGED;
            case PAYMENT_PENDING -> RecoveryClassification.LOOKUP_PAYMENT;
            case CHARGED -> RecoveryClassification.REPLAY_DOMAIN;
            case DOMAIN_COMMITTED -> RecoveryClassification.REBUILD_RUNTIME;
            case ACTIVE, FAILED, COMPENSATED -> RecoveryClassification.NO_OP;
            case COMPENSATION_PENDING -> RecoveryClassification.RETRY_COMPENSATION;
            case NEEDS_RECONCILIATION -> RecoveryClassification.WAIT_FOR_OPERATOR;
            case RESOLVED, REFUNDED, IGNORED -> RecoveryClassification.NO_OP;
        };
    }

    public boolean canTransitionTo(LedgerState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            // CREATED rows normally enter the payment flow, but a domain-first
            // refund records its row and then commits the domain straight away:
            // the money moves only after DOMAIN_COMMITTED. NEEDS_RECONCILIATION
            // stays available as the fail-closed escape for corrupt payloads.
            case CREATED -> next == PAYMENT_PENDING
                    || next == DOMAIN_COMMITTED
                    || next == FAILED
                    || next == NEEDS_RECONCILIATION;
            case PAYMENT_PENDING -> next == CHARGED || next == FAILED || next == NEEDS_RECONCILIATION;
            case CHARGED -> next == DOMAIN_COMMITTED
                    || next == COMPENSATION_PENDING
                    || next == NEEDS_RECONCILIATION;
            // A domain-first refund commits its domain while DOMAIN_COMMITTED
            // and only then moves money. A failed deposit parks the row in
            // COMPENSATION_PENDING (the shared recovery retry contract) and a
            // confirmed deposit settles it as COMPENSATED, both straight from
            // DOMAIN_COMMITTED. Claim rows never take these two edges: their
            // money moves before the domain commit, so their post-commit path
            // only rebuilds the runtime towards ACTIVE.
            case DOMAIN_COMMITTED -> next == ACTIVE
                    || next == COMPENSATION_PENDING
                    || next == COMPENSATED
                    || next == NEEDS_RECONCILIATION;
            case COMPENSATION_PENDING -> next == COMPENSATED || next == NEEDS_RECONCILIATION;
            // Operator verdicts leave NEEDS_RECONCILIATION exactly once, under
            // compare-and-set, and are themselves terminal: nothing recovers
            // out of them and they never re-enter the payment or compensation
            // flow.
            case NEEDS_RECONCILIATION -> next == RESOLVED || next == REFUNDED || next == IGNORED;
            case ACTIVE, FAILED, COMPENSATED, RESOLVED, REFUNDED, IGNORED -> false;
        };
    }

    /** Result classification used by startup recovery. */
    public enum RecoveryClassification {
        FAIL_UNCHARGED,
        LOOKUP_PAYMENT,
        REPLAY_DOMAIN,
        REBUILD_RUNTIME,
        NO_OP,
        RETRY_COMPENSATION,
        WAIT_FOR_OPERATOR,
        INVALID_RECORD
    }
}
