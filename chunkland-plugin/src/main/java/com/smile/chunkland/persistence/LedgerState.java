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
    NEEDS_RECONCILIATION;

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
        return this == ACTIVE || this == FAILED || this == COMPENSATED || this == NEEDS_RECONCILIATION;
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
        };
    }

    public boolean canTransitionTo(LedgerState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> next == PAYMENT_PENDING || next == FAILED;
            case PAYMENT_PENDING -> next == CHARGED || next == FAILED || next == NEEDS_RECONCILIATION;
            case CHARGED -> next == DOMAIN_COMMITTED
                    || next == COMPENSATION_PENDING
                    || next == NEEDS_RECONCILIATION;
            case DOMAIN_COMMITTED -> next == ACTIVE || next == NEEDS_RECONCILIATION;
            case COMPENSATION_PENDING -> next == COMPENSATED || next == NEEDS_RECONCILIATION;
            case ACTIVE, FAILED, COMPENSATED, NEEDS_RECONCILIATION -> false;
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
