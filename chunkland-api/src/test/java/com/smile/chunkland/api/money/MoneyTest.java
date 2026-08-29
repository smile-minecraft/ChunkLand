package com.smile.chunkland.api.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Red + behavioral tests for {@link Money}.
 *
 * <p>Money is an exact fixed-scale value object: the amount is stored as a {@code long}
 * of minor units and all arithmetic is exact integer arithmetic. There must be no
 * {@code double}/{@code float} currency math anywhere in this package (enforced both by
 * these behavioral tests and by {@link MoneyPackageNoFloatingPointTest}).
 */
class MoneyTest {

    private static final Currency CUR = Currency.of("TEST", 2);

    // ---- exact arithmetic (no floating point) ----

    @Test
    void addIsExactAndNeverUsesDouble() {
        Money a = new Money(100, CUR);
        Money b = new Money(200, CUR);
        assertEquals(new Money(300, CUR), a.add(b));
        // the classic double trap must not appear: 0.1 + 0.2 != 0.3
        Money tenth = new Money(10, CUR);   // 0.10
        Money twenty = new Money(20, CUR);  // 0.20
        assertEquals(new Money(30, CUR), tenth.add(twenty));
    }

    @Test
    void subtractIsExact() {
        assertEquals(new Money(50, CUR), new Money(250, CUR).subtract(new Money(200, CUR)));
        assertEquals(new Money(-50, CUR), new Money(150, CUR).subtract(new Money(200, CUR)));
    }

    @Test
    void multiplyByCountIsExact() {
        assertEquals(new Money(600, CUR), new Money(200, CUR).multiply(3));
        assertEquals(new Money(0, CUR), new Money(200, CUR).multiply(0));
        assertEquals(new Money(-400, CUR), new Money(200, CUR).multiply(-2));
    }

    @Test
    void negateFlipsSignExactly() {
        assertEquals(new Money(-100, CUR), new Money(100, CUR).negate());
        assertEquals(new Money(100, CUR), new Money(-100, CUR).negate());
        assertEquals(Money.zero(CUR), Money.zero(CUR).negate());
    }

    // ---- zero / negative ----

    @Test
    void zeroAndSignPredicates() {
        assertTrue(Money.zero(CUR).isZero());
        assertFalse(Money.zero(CUR).isNegative());
        assertTrue(new Money(-1, CUR).isNegative());
        assertTrue(new Money(1, CUR).isPositive());
        assertEquals(0, Money.zero(CUR).signum());
        assertEquals(-1, new Money(-5, CUR).signum());
        assertEquals(1, new Money(5, CUR).signum());
    }

    // ---- comparison / equality ----

    @Test
    void compareAndEqualsAreStable() {
        Money a = new Money(100, CUR);
        assertEquals(0, a.compareTo(new Money(100, CUR)));
        assertTrue(a.isLessThan(new Money(200, CUR)));
        assertTrue(a.isGreaterThan(new Money(50, CUR)));
        assertEquals(a, new Money(100, CUR));
        assertEquals(a.hashCode(), new Money(100, CUR).hashCode());
        assertNotEquals(a, new Money(100, Currency.of("OTHER", 2)));
        assertNotEquals(a, new Money(101, CUR));
    }

    @Test
    void currencyMismatchIsRejected() {
        Money other = new Money(100, Currency.of("OTHER", 2));
        assertThrows(IllegalArgumentException.class, () -> new Money(100, CUR).add(other));
        assertThrows(IllegalArgumentException.class, () -> new Money(100, CUR).subtract(other));
        assertThrows(IllegalArgumentException.class, () -> new Money(100, CUR).compareTo(other));
    }

    // ---- overflow has explicit behavior (exact arithmetic throws) ----

    @Test
    void addOverflowThrows() {
        Money max = new Money(Long.MAX_VALUE, CUR);
        assertThrows(ArithmeticException.class, () -> max.add(new Money(1, CUR)));
    }

    @Test
    void subtractUnderflowThrows() {
        Money min = new Money(Long.MIN_VALUE, CUR);
        assertThrows(ArithmeticException.class, () -> min.subtract(new Money(1, CUR)));
    }

    @Test
    void multiplyOverflowThrows() {
        Money big = new Money(Long.MAX_VALUE / 2 + 1, CUR);
        assertThrows(ArithmeticException.class, () -> big.multiply(2));
    }

    // ---- toString is stable and locale-independent ----

    @Test
    void toStringIsStable() {
        assertEquals("1.00 TEST", new Money(100, CUR).toString());
        assertEquals("0.00 TEST", Money.zero(CUR).toString());
        assertEquals("-0.50 TEST", new Money(-50, CUR).toString());
        assertEquals("12.34 TEST", new Money(1234, CUR).toString());
    }

    // ---- Long boundary rendering (no Math.abs on MIN_VALUE) ----

    @Test
    void toStringHandlesLongMinValue() {
        assertEquals("-92233720368547758.08 TEST", new Money(Long.MIN_VALUE, CUR).toString());
        assertEquals("-9223372036854775808 TEST", new Money(Long.MIN_VALUE, Currency.of("TEST", 0)).toString());
        assertEquals("-9.223372036854775808 TEST", new Money(Long.MIN_VALUE, Currency.of("TEST", 18)).toString());
    }

    @Test
    void toStringHandlesLongMaxValue() {
        assertEquals("92233720368547758.07 TEST", new Money(Long.MAX_VALUE, CUR).toString());
        assertEquals("9223372036854775807 TEST", new Money(Long.MAX_VALUE, Currency.of("TEST", 0)).toString());
    }
}
