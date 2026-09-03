package com.smile.chunkland.economy;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import org.junit.jupiter.api.Test;

class VaultMoneyConverterTest {

    private static final Currency USD2 = Currency.of("USD", 2);
    private static final Currency JPY0 = Currency.of("JPY", 0);
    private static final Currency BTC8 = Currency.of("BTC", 8);

    @Test
    void toVaultExactConversion() {
        Money m = new Money(199, USD2); // 1.99
        double d = VaultMoneyConverter.toVaultAmount(m);
        assertEquals(1.99, d, 0.0000001);
    }

    @Test
    void toVaultZero() {
        Money m = Money.zero(USD2);
        assertEquals(0.0, VaultMoneyConverter.toVaultAmount(m));
    }

    @Test
    void toVaultScaleZero() {
        Money m = new Money(42, JPY0);
        assertEquals(42.0, VaultMoneyConverter.toVaultAmount(m));
    }

    @Test
    void toVaultHighScale() {
        Money m = new Money(100_000_000L, BTC8); // 1.0
        assertEquals(1.0, VaultMoneyConverter.toVaultAmount(m), 1e-9);
    }

    @Test
    void toVaultNegativeFailsClosed() {
        Money m = new Money(-100, USD2);
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.toVaultAmount(m));
    }

    @Test
    void fromVaultHalfUpRounding() {
        // 0.005 with scale 2 -> 1 cent half-up
        Money m = VaultMoneyConverter.fromVaultAmount(0.005, USD2);
        assertEquals(1, m.minorUnits());
    }

    @Test
    void fromVaultHalfUpTie() {
        // 1.005 -> 101 cents (100.5 cents rounded half-up)
        Money m = VaultMoneyConverter.fromVaultAmount(1.015, USD2);
        assertEquals(102, m.minorUnits()); // 1.015*100 =101.5 ->102
    }

    @Test
    void fromVaultNanFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(Double.NaN, USD2));
    }

    @Test
    void fromVaultInfinityFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(Double.POSITIVE_INFINITY, USD2));
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(Double.NEGATIVE_INFINITY, USD2));
    }

    @Test
    void fromVaultNegativeFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(-1.0, USD2));
    }

    @Test
    void fromVaultOverflowFailsClosed() {
        // > Long.MAX_VALUE with scale 0 -> overflow long
        assertThrows(ArithmeticException.class, () -> VaultMoneyConverter.fromVaultAmount(1e19, JPY0));
    }

    @Test
    void toVaultNoDoubleArithmeticInMoney() {
        // Domain Money arithmetic remains exact; converter is only place with double
        Money a = new Money(100, USD2);
        Money b = new Money(50, USD2);
        Money sum = a.add(b);
        assertEquals(150, sum.minorUnits());
        // converter still works after arithmetic
        assertEquals(1.5, VaultMoneyConverter.toVaultAmount(sum), 1e-9);
    }

    @Test
    void fromVaultExactRoundtrip() {
        Money original = new Money(12345, USD2);
        double d = VaultMoneyConverter.toVaultAmount(original);
        Money back = VaultMoneyConverter.fromVaultAmount(d, USD2);
        assertEquals(original, back);
    }
}
