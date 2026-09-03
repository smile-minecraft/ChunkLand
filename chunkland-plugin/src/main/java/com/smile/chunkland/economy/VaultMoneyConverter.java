package com.smile.chunkland.economy;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Single boundary where {@link Money} (exact minor-unit long) crosses to/from
 * Vault's {@code double} major units. No other class in the codebase may
 * perform currency math in {@code double}.
 *
 * <p>Policy:
 * <ul>
 *   <li>Money → double: exact division via {@link BigDecimal}, then
 *       {@code doubleValue()}. Fail closed if result is not finite,
 *       negative, NaN/infinite, or currency scale invalid.</li>
 *   <li>double → Money: {@code BigDecimal.valueOf(double)} scaled by currency
 *       {@code scale} with {@link RoundingMode#HALF_UP}, then
 *       {@code longValueExact()}. Fail closed if amount is non-finite,
 *       negative, NaN/infinite, or overflows {@code long}.</li>
 *   <li>Negative minor units always fail closed.</li>
 *   <li>Rounding is half-up when converting double → Money; Money → double
 *       is exact division then double representation (no additional rounding).</li>
 * </ul>
 */
public final class VaultMoneyConverter {

    private VaultMoneyConverter() {}

    /**
     * Convert exact {@link Money} to Vault major-unit double.
     *
     * @throws NullPointerException     if money or its currency is null
     * @throws IllegalArgumentException if minorUnits negative, scale invalid,
     *                                  or amount is negative after conversion
     * @throws ArithmeticException      if conversion overflows to non-finite double
     */
    public static double toVaultAmount(Money money) {
        Objects.requireNonNull(money, "money");
        Currency currency = money.currency();
        Objects.requireNonNull(currency, "currency");
        long minorUnits = money.minorUnits();
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Vault conversion requires non-negative money, got " + money);
        }
        int scale = currency.scale();
        // Currency already guards 0 <= scale <= 18
        BigDecimal major = BigDecimal.valueOf(minorUnits).movePointLeft(scale);
        double d = major.doubleValue();
        if (!Double.isFinite(d)) {
            throw new ArithmeticException("Vault double overflow for money " + money);
        }
        if (d < 0 || Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Vault double must be finite non-negative, got " + d);
        }
        return d;
    }

    /**
     * Convert Vault major-unit double to exact {@link Money} with half-up rounding.
     *
     * @throws NullPointerException     if currency is null
     * @throws IllegalArgumentException if amount is NaN, infinite, or negative
     * @throws ArithmeticException      if scaled value overflows long
     */
    public static Money fromVaultAmount(double amount, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("Vault amount must be finite, got " + amount);
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Vault amount must not be NaN/infinite, got " + amount);
        }
        if (amount < 0) {
            throw new IllegalArgumentException("Vault amount must not be negative, got " + amount);
        }
        BigDecimal bd = BigDecimal.valueOf(amount);
        BigDecimal scaled = bd.scaleByPowerOfTen(currency.scale())
                .setScale(0, RoundingMode.HALF_UP);
        long minorUnits = scaled.longValueExact();
        return new Money(minorUnits, currency);
    }
}
