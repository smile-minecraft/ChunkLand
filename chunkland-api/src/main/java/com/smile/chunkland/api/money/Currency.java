package com.smile.chunkland.api.money;

import java.util.Objects;

/**
 * Immutable currency descriptor for exact fixed-scale money (spec §48).
 *
 * <p>A currency is identified by a code and a {@code scale}: the number of minor units
 * per whole unit (e.g. scale {@code 2} means 100 minor units = 1 whole unit). Money is
 * always stored and computed in minor units as a {@code long}, so the scale only affects
 * display and never participates in arithmetic. Rounding policy (e.g. for the Vault
 * adapter boundary) lives outside this value object, because internal money math is exact
 * and never converts to {@code double} (spec §48: "對 Vault 的 double 僅在 Adapter Boundary 轉換").
 *
 * <p>Thread-safe: a pure value object with no mutable state.
 */
public record Currency(String code, int scale) {

    public Currency {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("currency code must not be blank");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("scale must be >= 0");
        }
        if (scale > 18) {
            throw new IllegalArgumentException("scale must be <= 18 (minor-unit overflow guard)");
        }
    }

    /** Build a currency from a code and minor-units-per-unit scale. */
    public static Currency of(String code, int scale) {
        return new Currency(code, scale);
    }
}
