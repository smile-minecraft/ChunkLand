package com.smile.chunkland.persistence;

import java.util.Objects;

/** Explicit result of an Economy transaction lookup. */
public record PaymentLookup(Status status, String transactionRef) {
    public enum Status {
        CONFIRMED_UNPAID,
        CONFIRMED_PAID,
        UNKNOWN
    }

    public PaymentLookup {
        Objects.requireNonNull(status, "status");
        if (status == Status.CONFIRMED_PAID && (transactionRef == null || transactionRef.isBlank())) {
            throw new IllegalArgumentException("a confirmed payment requires a transaction reference");
        }
        if (status != Status.CONFIRMED_PAID && transactionRef != null) {
            throw new IllegalArgumentException("only a confirmed payment may have a transaction reference");
        }
    }

    public static PaymentLookup unpaid() {
        return new PaymentLookup(Status.CONFIRMED_UNPAID, null);
    }

    public static PaymentLookup paid(String transactionRef) {
        return new PaymentLookup(Status.CONFIRMED_PAID, transactionRef);
    }

    public static PaymentLookup unknown() {
        return new PaymentLookup(Status.UNKNOWN, null);
    }
}
