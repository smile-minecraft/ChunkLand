package com.smile.chunkland.command;

import com.smile.chunkland.api.money.Currency;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Renders exact minor-unit money as the major-unit text a player reads, for
 * example {@code 50} at scale {@code 2} becomes {@code "0.50 EMC"}.
 *
 * <p>The value is always shown with exactly {@code currency.scale()} fraction
 * digits and never in scientific notation, so the displayed amount matches the
 * stored minor units exactly (no rounding and no {@code double}). The currency
 * code is appended after a single space.
 */
public final class MoneyDisplay {

    private MoneyDisplay() {}

    /**
     * Format minor units as {@code "<major> <code>"} with a fixed scale.
     *
     * @param minorUnits exact amount in minor units; must be non-negative
     * @param currency   currency descriptor supplying the scale and code
     * @return the human-readable amount, e.g. {@code "0.50 EMC"}
     * @throws NullPointerException     if {@code currency} is null
     * @throws IllegalArgumentException if {@code minorUnits} is negative
     */
    public static String format(long minorUnits, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "money display requires non-negative minor units, got " + minorUnits);
        }
        return BigDecimal.valueOf(minorUnits)
                .movePointLeft(currency.scale())
                .toPlainString()
                + " " + currency.code();
    }
}
