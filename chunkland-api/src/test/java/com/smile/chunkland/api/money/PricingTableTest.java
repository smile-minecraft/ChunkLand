package com.smile.chunkland.api.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Red + behavioral tests for {@link PricingTable} and {@link PricingTier}.
 *
 * <p>The pricing basis is the owner's global total chunk count across all Player Lands
 * (spec §49), NOT the current transaction's chunk count and NOT a single Land's size.
 * Tiers form a contiguous, non-overlapping partition of chunk indices {@code [1, ∞)}
 * where {@code until == -1} is the unbounded top tier.
 */
class PricingTableTest {

    private static final Currency CUR = Currency.of("TEST", 2);

    /** The exact §49 progressive example. Prices are minor units. */
    private static PricingTable section49() {
        return PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, CUR)),
                PricingTier.of(50, new Money(200, CUR)),
                PricingTier.of(100, new Money(400, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR))));
    }

    // ---- §49 progressive example correctness ----

    @Test
    void section49MarginalPricesMatchTierBoundaries() {
        PricingTable t = section49();
        // chunk #1 (owner has 0) -> first tier
        assertEquals(new Money(100, CUR), t.marginalPrice(0));
        // chunk #21 (owner has 20) -> second tier
        assertEquals(new Money(200, CUR), t.marginalPrice(20));
        // chunk #51 (owner has 50) -> third tier
        assertEquals(new Money(400, CUR), t.marginalPrice(50));
        // chunk #101 (owner has 100) -> unbounded top tier
        assertEquals(new Money(800, CUR), t.marginalPrice(100));
    }

    @Test
    void section49ProgressiveTotalFromZero() {
        PricingTable t = section49();
        // 20 chunks at 100 = 2000
        assertEquals(new Money(2000, CUR), t.priceForClaim(0, 20));
        // 21 chunks: 20*100 + 1*200 = 2200
        assertEquals(new Money(2200, CUR), t.priceForClaim(0, 21));
        // 30 chunks: 20*100 + 10*200 = 4000
        assertEquals(new Money(4000, CUR), t.priceForClaim(0, 30));
        // 100 chunks: 20*100 + 30*200 + 50*400 = 28000
        assertEquals(new Money(28000, CUR), t.priceForClaim(0, 100));
        // 101 chunks: + 1*800 = 28800
        assertEquals(new Money(28800, CUR), t.priceForClaim(0, 101));
    }

    // ---- basis is owner GLOBAL total, not the transaction count ----

    @Test
    void pricingUsesGlobalTotalNotTransactionCount() {
        PricingTable t = section49();
        // Owner already holds 25 chunks globally; claims 5 more (chunks 26..30).
        // All five fall in the second tier (until:50, price 200) -> 5 * 200 = 1000.
        assertEquals(new Money(1000, CUR), t.priceForClaim(25, 5));

        // Contrast: if the transaction count (5) were wrongly used as the basis,
        // chunks 6..10 would be priced at the first tier (100) -> 500. The contract
        // must NOT produce that result.
        assertFalse(t.priceForClaim(25, 5).equals(new Money(500, CUR)),
                "must price by owner global total, not by transaction count");
    }

    @Test
    void marginalPriceReflectsGlobalTotalAcrossTiers() {
        PricingTable t = section49();
        // Owner holds 49 globally; the next chunk (#50) is still in tier 2 (<=50) at 200.
        assertEquals(new Money(200, CUR), t.marginalPrice(49));
        // Owner holds 50 globally; the next chunk (#51) jumps to tier 3 at 400.
        assertEquals(new Money(400, CUR), t.marginalPrice(50));
    }

    // ---- order independence of the pricing function ----

    @Test
    void claimInOneShotEqualsSequentialClaims() {
        PricingTable t = section49();
        Money oneShot = t.priceForClaim(0, 30);
        long running = 0;
        Money sequential = Money.zero(CUR);
        for (int i = 0; i < 3; i++) {
            sequential = sequential.add(t.priceForClaim(running, 10));
            running += 10;
        }
        assertEquals(oneShot, sequential);
    }

    // ---- immutability ----

    @Test
    void tableIsImmutableAndInputListIsCopied() {
        List<PricingTier> input = new ArrayList<>(section49().tiers());
        PricingTable t = PricingTable.of(input);
        input.clear();
        assertEquals(4, t.tiers().size());
        assertThrows(UnsupportedOperationException.class, () -> t.tiers().add(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(1, CUR))));
    }

    // ---- invalid tier configurations are rejected ----

    @Test
    void overlappingTiersRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, CUR)),
                PricingTier.of(20, new Money(200, CUR)), // same until -> overlap
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR)))));
    }

    @Test
    void nonMonotonicTiersRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(50, new Money(200, CUR)),
                PricingTier.of(20, new Money(100, CUR)), // decreasing -> overlap/gap
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR)))));
    }

    @Test
    void gapAtTopRejectedWhenNoUnboundedTier() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, CUR)),
                PricingTier.of(50, new Money(200, CUR))))); // finite top -> gap beyond 50
    }

    @Test
    void negativeFiniteUntilRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(-5, new Money(100, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR)))));
    }

    @Test
    void negativePriceRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(20, new Money(-100, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR)))));
    }

    @Test
    void mixedCurrencyTiersRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, Currency.of("OTHER", 2))))));
    }

    @Test
    void emptyTiersRejected() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of()));
    }

    @Test
    void unboundedTierMustBeLast() {
        assertThrows(IllegalArgumentException.class, () -> PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR)),
                PricingTier.of(20, new Money(100, CUR)))));
    }

    // ---- invalid pricing inputs are rejected ----

    @Test
    void negativeOwnerTotalRejected() {
        assertThrows(IllegalArgumentException.class, () -> section49().priceForClaim(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> section49().marginalPrice(-1));
    }

    @Test
    void nonPositiveAdditionalChunksRejected() {
        assertThrows(IllegalArgumentException.class, () -> section49().priceForClaim(0, 0));
        assertThrows(IllegalArgumentException.class, () -> section49().priceForClaim(0, -3));
    }

    // ---- flat pricing is a single unbounded tier ----

    @Test
    void flatPricingSingleTier() {
        PricingTable flat = PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(100, CUR))));
        assertEquals(new Money(100, CUR), flat.marginalPrice(0));
        assertEquals(new Money(100, CUR), flat.marginalPrice(999));
        assertEquals(new Money(500, CUR), flat.priceForClaim(0, 5));
    }

    @Test
    void tierCoversIsContiguousAndDeterministic() {
        PricingTier t = PricingTier.of(50, new Money(200, CUR));
        assertTrue(t.covers(1));
        assertTrue(t.covers(50));
        assertFalse(t.covers(51));
        assertThrows(IllegalArgumentException.class, () -> t.covers(0));
    }

    // ---- Long boundary: no wrap, no double count, overflow rejected ----

    @Test
    void finiteTierAtLongMaxDoesNotDoubleCountUnbounded() {
        PricingTable t = PricingTable.of(List.of(
                PricingTier.of(Long.MAX_VALUE, new Money(1, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(2, CUR))));
        // Chunks 1..Long.MAX_VALUE all fall in the finite tier; the unbounded tier counts nothing.
        assertEquals(new Money(Long.MAX_VALUE, CUR), t.priceForClaim(0, Long.MAX_VALUE));
        assertEquals(new Money(1, CUR), t.marginalPrice(0));
        assertEquals(new Money(1, CUR), t.marginalPrice(Long.MAX_VALUE - 1));
    }

    @Test
    void countMultiplicationOverflowThrows() {
        PricingTable t = PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(Long.MAX_VALUE, CUR))));
        assertThrows(ArithmeticException.class, () -> t.priceForClaim(0, 2));
    }

    @Test
    void ownerBasisOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> section49().priceForClaim(Long.MAX_VALUE, 1));
        assertThrows(ArithmeticException.class, () -> section49().priceForClaim(Long.MAX_VALUE - 1, 2));
        assertThrows(ArithmeticException.class, () -> section49().marginalPrice(Long.MAX_VALUE));
    }
}
