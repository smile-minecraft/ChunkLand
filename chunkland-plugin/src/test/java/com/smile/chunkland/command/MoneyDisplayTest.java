package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smile.chunkland.api.money.Currency;
import org.junit.jupiter.api.Test;

/**
 * Display formatting for exact minor-unit money: the refund a player sees must
 * be human-readable major units with the currency code, never the raw internal
 * minor-unit long.
 */
class MoneyDisplayTest {

    private static final Currency EMC = Currency.of("EMC", 2);

    @Test
    void formatsMinorUnitsWithFixedScaleAndCode() {
        assertEquals("0.50 EMC", MoneyDisplay.format(50L, EMC));
    }

    @Test
    void formatsZeroWithFixedScale() {
        assertEquals("0.00 EMC", MoneyDisplay.format(0L, EMC));
    }

    @Test
    void scaleZeroRendersWholeUnits() {
        assertEquals("50 GOLD", MoneyDisplay.format(50L, Currency.of("GOLD", 0)));
    }

    @Test
    void rendersExactlyScaleFractionDigits() {
        assertEquals("0.417 XYZ", MoneyDisplay.format(417L, Currency.of("XYZ", 3)));
        assertEquals("1.00 EMC", MoneyDisplay.format(100L, EMC));
        assertEquals("0.05 EMC", MoneyDisplay.format(5L, EMC));
    }

    @Test
    void rendersLargeValuesWithoutScientificNotation() {
        assertEquals("1234567.89 EMC", MoneyDisplay.format(123456789L, EMC));
        assertEquals("92233720368547758.07 EMC", MoneyDisplay.format(Long.MAX_VALUE, EMC));
    }

    @Test
    void rejectsNegativeMinorUnits() {
        assertThrows(IllegalArgumentException.class, () -> MoneyDisplay.format(-1L, EMC));
    }

    @Test
    void rejectsNullCurrency() {
        assertThrows(NullPointerException.class, () -> MoneyDisplay.format(50L, null));
    }

    @Test
    void illegalScaleRejectedAtCurrencyConstruction() {
        assertThrows(IllegalArgumentException.class, () -> Currency.of("EMC", -1));
        assertThrows(IllegalArgumentException.class, () -> Currency.of("EMC", 19));
    }
}
