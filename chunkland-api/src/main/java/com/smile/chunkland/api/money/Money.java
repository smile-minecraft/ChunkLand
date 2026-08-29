package com.smile.chunkland.api.money;

import java.util.Objects;

/**
 * Exact fixed-scale money value object (spec §10, §48, §49).
 *
 * <p>The amount is stored as a {@code long} of minor units; all arithmetic is exact integer
 * arithmetic via {@link Math#addExact}, {@link Math#subtractExact}, {@link Math#multiplyExact}
 * and {@link Math#negateExact}. There is deliberately no {@code double}/{@code float} currency
 * math anywhere in this package — overflow is never silently wrapped, it throws
 * {@link ArithmeticException} so a caller must decide how to react (spec: "overflow 有明確行為").
 *
 * <p>Money is comparable and equatable only within the same {@link Currency}; mixing currencies
 * is rejected. The object is immutable and thread-safe.
 */
public final class Money implements Comparable<Money> {

    private final long minorUnits;
    private final Currency currency;

    public Money(long minorUnits, Currency currency) {
        this.minorUnits = minorUnits;
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** The zero amount in the given currency. */
    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public long minorUnits() {
        return minorUnits;
    }

    public Currency currency() {
        return currency;
    }

    /** Exact addition; throws {@link ArithmeticException} on overflow. */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /** Exact subtraction; throws {@link ArithmeticException} on overflow. */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /** Exact multiplication by an exact integer count; throws {@link ArithmeticException} on overflow. */
    public Money multiply(long factor) {
        return new Money(Math.multiplyExact(minorUnits, factor), currency);
    }

    /** Exact negation; throws {@link ArithmeticException} on {@link Long#MIN_VALUE}. */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public int signum() {
        return Long.compare(minorUnits, 0L);
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Money other)) {
            return false;
        }
        return minorUnits == other.minorUnits && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minorUnits, currency);
    }

    /**
     * Stable, locale-independent rendering as {@code <major>.<minor> <code>}.
     *
     * <p>Minor digits are zero-padded to {@link Currency#scale()} using only integer math, so
     * the output never depends on the JVM default locale (no comma decimal separators).
     */
    @Override
    public String toString() {
        // Render via Long.toString so Long.MIN_VALUE is exact (Math.abs(MIN) == MIN, which
        // would corrupt a sign/magnitude split). The decimal digits of |minorUnits| are taken
        // from the magnitude substring, then split at the scale — pure integer/string math,
        // locale-independent, no double/float.
        String raw = Long.toString(minorUnits);
        String sign;
        String magnitude;
        if (raw.charAt(0) == '-') {
            sign = "-";
            magnitude = raw.substring(1);
        } else {
            sign = "";
            magnitude = raw;
        }
        int scale = currency.scale();
        if (scale == 0) {
            return sign + magnitude + " " + currency.code();
        }
        String major;
        String minorDigits;
        if (magnitude.length() <= scale) {
            major = "0";
            minorDigits = magnitude;
        } else {
            major = magnitude.substring(0, magnitude.length() - scale);
            minorDigits = magnitude.substring(magnitude.length() - scale);
        }
        StringBuilder padded = new StringBuilder(minorDigits);
        while (padded.length() < scale) {
            padded.insert(0, '0');
        }
        return sign + major + "." + padded + " " + currency.code();
    }
}
