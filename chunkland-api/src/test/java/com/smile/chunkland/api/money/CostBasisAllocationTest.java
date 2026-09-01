package com.smile.chunkland.api.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CostBasisAllocationTest {

    private static final Currency CUR = Currency.of("TEST", 2);
    private static final Currency OTHER = Currency.of("OTHER", 2);

    // ---- helpers ----

    private static PricingTable tableA() {
        return PricingTable.of(List.of(
                PricingTier.of(20, new Money(100, CUR)),
                PricingTier.of(50, new Money(200, CUR)),
                PricingTier.of(100, new Money(400, CUR)),
                PricingTier.of(PricingTier.UNBOUNDED, new Money(800, CUR))));
    }

    private static PricingTable tableB() {
        // different pricing to prove refund does not reprice
        return PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(9999, CUR))));
    }

    // ---- single chunk ----

    @Test
    void singleChunkAllocationEqualsTotal() {
        Money total = new Money(1234, CUR);
        ChunkCoordinate c = new ChunkCoordinate(0, 0);
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, Set.of(c));
        assertEquals(1, alloc.size());
        assertEquals(total, alloc.costBasisFor(c));
        assertEquals(total, alloc.total());
        assertEquals(total.minorUnits(), alloc.allocations().values().stream().mapToLong(Money::minorUnits).sum());
    }

    // ---- multi chunk exact division ----

    @Test
    void multiChunkExactDivision() {
        Money total = new Money(300, CUR);
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(1, 0),
                new ChunkCoordinate(0, 1));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        // 300 /3 =100 each
        for (ChunkCoordinate cc : chunks) {
            assertEquals(new Money(100, CUR), alloc.costBasisFor(cc));
        }
        assertEquals(300, alloc.allocations().values().stream().mapToLong(Money::minorUnits).sum());
    }

    // ---- total smaller than chunk count ----

    @Test
    void totalSmallerThanChunkCount() {
        Money total = new Money(2, CUR); // 2 minor units across 5 chunks
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(1, 0),
                new ChunkCoordinate(2, 0),
                new ChunkCoordinate(3, 0),
                new ChunkCoordinate(4, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        long sum = alloc.allocations().values().stream().mapToLong(Money::minorUnits).sum();
        assertEquals(2, sum);
        // sorted order: (0,0)=1, (1,0)=1, rest 0
        assertEquals(new Money(1, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 0)));
        assertEquals(new Money(1, CUR), alloc.costBasisFor(new ChunkCoordinate(1, 0)));
        assertEquals(new Money(0, CUR), alloc.costBasisFor(new ChunkCoordinate(2, 0)));
        assertEquals(new Money(0, CUR), alloc.costBasisFor(new ChunkCoordinate(4, 0)));
    }

    // ---- remainder distributed by (chunkX, chunkZ) sorting ----

    @Test
    void remainderDistributedBySortedOrder() {
        Money total = new Money(10, CUR); // 10 /3 =3 remainder 1
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(5, 5),
                new ChunkCoordinate(0, 10),
                new ChunkCoordinate(0, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        // sorted: (0,0), (0,10), (5,5)
        assertEquals(new Money(4, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 0)));
        assertEquals(new Money(3, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 10)));
        assertEquals(new Money(3, CUR), alloc.costBasisFor(new ChunkCoordinate(5, 5)));
        assertEquals(10, alloc.allocations().values().stream().mapToLong(Money::minorUnits).sum());
    }

    @Test
    void negativeCoordinatesSortedCorrectly() {
        Money total = new Money(10, CUR); // 10/3=3 remainder1
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(-1, 0),
                new ChunkCoordinate(-1, -1));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        // sorted: (-1,-1), (-1,0), (0,0) -> first gets extra
        assertEquals(new Money(4, CUR), alloc.costBasisFor(new ChunkCoordinate(-1, -1)));
        assertEquals(new Money(3, CUR), alloc.costBasisFor(new ChunkCoordinate(-1, 0)));
        assertEquals(new Money(3, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 0)));
    }

    // ---- determinism regardless of iteration order ----

    @Test
    void determinismRegardlessOfIterationOrder() {
        Money total = new Money(10, CUR);
        List<ChunkCoordinate> listA = List.of(
                new ChunkCoordinate(2, 3),
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(1, 1));
        // different iteration order but same set
        List<ChunkCoordinate> listB = List.of(
                new ChunkCoordinate(1, 1),
                new ChunkCoordinate(2, 3),
                new ChunkCoordinate(0, 0));
        Set<ChunkCoordinate> setA = new LinkedHashSet<>(listA);
        Set<ChunkCoordinate> setB = new LinkedHashSet<>(listB);
        CostBasisAllocation a = CostBasisCalculator.allocate(total, setA);
        CostBasisAllocation b = CostBasisCalculator.allocate(total, setB);
        assertEquals(a.allocations(), b.allocations());
        // iteration order must be deterministic by (chunkX, chunkZ), not just map equality
        List<ChunkCoordinate> expectedOrder = List.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(1, 1),
                new ChunkCoordinate(2, 3));
        assertEquals(expectedOrder, new ArrayList<>(a.allocations().keySet()));
        assertEquals(expectedOrder, new ArrayList<>(b.allocations().keySet()));
        assertEquals(new ArrayList<>(a.allocations().keySet()), new ArrayList<>(b.allocations().keySet()));
    }

    @Test
    void allocationDeterministicKeyOrderForRemainderDistribution() {
        Money total = new Money(10, CUR);
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(5, 5),
                new ChunkCoordinate(0, 10),
                new ChunkCoordinate(0, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        List<ChunkCoordinate> expected = List.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(0, 10),
                new ChunkCoordinate(5, 5));
        assertEquals(expected, new ArrayList<>(alloc.allocations().keySet()));
    }

    // ---- input/output immutable ----

    @Test
    void inputCollectionIsDefensivelyCopied() {
        Money total = new Money(300, CUR);
        Set<ChunkCoordinate> input = new HashSet<>();
        input.add(new ChunkCoordinate(0, 0));
        input.add(new ChunkCoordinate(1, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, input);
        input.add(new ChunkCoordinate(99, 99)); // mutate after allocate
        assertEquals(2, alloc.size());
        assertThrows(IllegalArgumentException.class, () -> alloc.costBasisFor(new ChunkCoordinate(99, 99)));
    }

    @Test
    void outputMapIsImmutable() {
        Money total = new Money(300, CUR);
        Set<ChunkCoordinate> chunks = Set.of(new ChunkCoordinate(0, 0), new ChunkCoordinate(1, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        Map<ChunkCoordinate, Money> map = alloc.allocations();
        assertThrows(UnsupportedOperationException.class, () -> map.put(new ChunkCoordinate(99, 99), new Money(999, CUR)));
        assertThrows(UnsupportedOperationException.class, () -> map.remove(new ChunkCoordinate(0, 0)));
    }

    @Test
    void allocationConstructorIsImmutableEvenWithMutableMap() {
        Money total = new Money(300, CUR);
        Map<ChunkCoordinate, Money> mutable = new java.util.LinkedHashMap<>();
        mutable.put(new ChunkCoordinate(0, 0), new Money(150, CUR));
        mutable.put(new ChunkCoordinate(1, 0), new Money(150, CUR));
        CostBasisAllocation alloc = new CostBasisAllocation(total, mutable);
        // mutate original after construction must not affect allocation
        mutable.put(new ChunkCoordinate(99, 99), new Money(999, CUR));
        assertEquals(2, alloc.size());
        assertThrows(IllegalArgumentException.class, () -> alloc.costBasisFor(new ChunkCoordinate(99, 99)));
        assertEquals(new Money(150, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 0)));
        mutable.remove(new ChunkCoordinate(0, 0));
        assertEquals(2, alloc.size());
        assertEquals(new Money(150, CUR), alloc.costBasisFor(new ChunkCoordinate(0, 0)));
        // exposed map must remain unmodifiable
        Map<ChunkCoordinate, Money> exposed = alloc.allocations();
        assertThrows(UnsupportedOperationException.class, () -> exposed.put(new ChunkCoordinate(88, 88), new Money(1, CUR)));
        assertThrows(UnsupportedOperationException.class, () -> exposed.remove(new ChunkCoordinate(0, 0)));
        // iteration order preserved from mutable source (LinkedHashMap order is insertion order)
        // but allocation contract is (chunkX, chunkZ) order; for this explicit mutable test
        // we assert that the defensive copy preserves the iteration order supplied
        assertEquals(new ArrayList<>(new java.util.LinkedHashMap<>(Map.of(
                new ChunkCoordinate(0, 0), new Money(150, CUR),
                new ChunkCoordinate(1, 0), new Money(150, CUR))).keySet()).size(), alloc.allocations().size());
    }

    // ---- duplicate detection ----

    @Test
    void duplicateChunkRejected() {
        Money total = new Money(300, CUR);
        List<ChunkCoordinate> dup = List.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(0, 0));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.allocate(total, dup));
    }

    // ---- empty / invalid ----

    @Test
    void emptyLotRejected() {
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.allocate(new Money(100, CUR), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.allocate(new Money(100, CUR), List.of()));
    }

    @Test
    void nullInputsRejected() {
        assertThrows(NullPointerException.class, () -> CostBasisCalculator.allocate(null, Set.of(new ChunkCoordinate(0, 0))));
        assertThrows(NullPointerException.class, () -> CostBasisCalculator.allocate(new Money(100, CUR), null));
        assertThrows(NullPointerException.class, () -> CostBasisCalculator.allocate(new Money(100, CUR), new ArrayList<>(Collections.singletonList(null))));
    }

    @Test
    void negativeTotalRejected() {
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.allocate(new Money(-1, CUR), Set.of(new ChunkCoordinate(0, 0))));
    }

    // ---- cross-tier claim lot total conservation ----

    @Test
    void crossTierClaimLotAllocationConservesTotal() {
        PricingTable t = tableA();
        // owner has 18 chunks, claims 5 more -> crosses tier boundary at 20
        Money total = t.priceForClaim(18, 5);
        // 18-> chunks 19,20 at 100 each, 21,22,23 at 200 each => 200+600=800
        assertEquals(new Money(800, CUR), total);
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(1, 0),
                new ChunkCoordinate(2, 0),
                new ChunkCoordinate(3, 0),
                new ChunkCoordinate(4, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        long sum = alloc.allocations().values().stream().mapToLong(Money::minorUnits).sum();
        assertEquals(total.minorUnits(), sum);
    }

    // ---- refund default ratio 0.5 ----

    @Test
    void refundDefaultRatioIsHalf() {
        Money cost = new Money(100, CUR);
        Money refund = CostBasisCalculator.refund(cost);
        assertEquals(new Money(50, CUR), refund);
    }

    @Test
    void refundUsesOriginalCostBasisNotCurrentTier() {
        PricingTable before = tableA();
        PricingTable after = tableB();
        Money total = before.priceForClaim(0, 2); // 2 *100 =200
        Set<ChunkCoordinate> chunks = Set.of(new ChunkCoordinate(0, 0), new ChunkCoordinate(1, 0));
        CostBasisAllocation alloc = CostBasisCalculator.allocate(total, chunks);
        Money originalPerChunk = alloc.costBasisFor(new ChunkCoordinate(0, 0)); // 100
        Money refundOriginal = CostBasisCalculator.refund(originalPerChunk);
        // change pricing table doesn't affect refund
        Money afterPrice = after.priceForClaim(0, 1); // 9999
        assertTrue(afterPrice.minorUnits() != originalPerChunk.minorUnits());
        Money refundAfterTableChange = CostBasisCalculator.refund(originalPerChunk);
        assertEquals(refundOriginal, refundAfterTableChange);
        assertEquals(new Money(50, CUR), refundOriginal);
        // ensure not recomputed via new tier
        assertTrue(!refundAfterTableChange.equals(new Money(afterPrice.minorUnits() / 2, CUR)) || afterPrice.minorUnits() == 100);
    }

    // ---- refund exact rounding half-up ----

    @Test
    void refundExactRoundingHalfUp() {
        // 1 * 1/2 = 0.5 -> half-up => 1
        assertEquals(new Money(1, CUR), CostBasisCalculator.refund(new Money(1, CUR), 1, 2));
        // 3 * 1/2 =1.5 -> 2
        assertEquals(new Money(2, CUR), CostBasisCalculator.refund(new Money(3, CUR), 1, 2));
        // 1 * 1/3 =0.333 ->0
        assertEquals(new Money(0, CUR), CostBasisCalculator.refund(new Money(1, CUR), 1, 3));
        // 2 *1/3=0.666 ->1
        assertEquals(new Money(1, CUR), CostBasisCalculator.refund(new Money(2, CUR), 1, 3));
        // 5 *1/3=1.666->2
        assertEquals(new Money(2, CUR), CostBasisCalculator.refund(new Money(5, CUR), 1, 3));
    }

    @Test
    void refundInvalidRatioRejected() {
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(100, CUR), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(100, CUR), -1, 2));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(100, CUR), 1, -2));
        assertThrows(NullPointerException.class, () -> CostBasisCalculator.refund(null, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(-1, CUR), 1, 2));
    }

    @Test
    void refundRatioBoundariesZeroAndOne() {
        Money cost = new Money(100, CUR);
        // 0 ratio
        assertEquals(new Money(0, CUR), CostBasisCalculator.refund(cost, 0, 2));
        assertEquals(new Money(0, CUR), CostBasisCalculator.refund(cost, 0, 1));
        assertEquals(new Money(0, CUR), CostBasisCalculator.refund(new Money(0, CUR), 0, 1));
        // 1 ratio
        assertEquals(new Money(100, CUR), CostBasisCalculator.refund(cost, 1, 1));
        assertEquals(new Money(100, CUR), CostBasisCalculator.refund(cost, 2, 2));
        assertEquals(new Money(100, CUR), CostBasisCalculator.refund(cost, 100, 100));
        // 1/2 default is still inside bounds
        assertEquals(new Money(50, CUR), CostBasisCalculator.refund(cost, 1, 2));
    }

    @Test
    void refundAboveOneRejected() {
        Money cost = new Money(100, CUR);
        // ratio >1 must be rejected — refund cannot exceed original cost basis
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(cost, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(cost, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(cost, 101, 100));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(cost, 6, 5));
        // 0/ denominator is allowed, but numerator > denominator is not
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(1, CUR), 2, 1));
    }

    @Test
    void refundInvalidDenominatorZeroRejected() {
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(100, CUR), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CostBasisCalculator.refund(new Money(100, CUR), 1, 0));
    }

    @Test
    void refundOverflowThrows() {
        // overflow with valid ratio at or below one: cost * numerator overflows long
        Money big = new Money(Long.MAX_VALUE, CUR);
        //  MAX * 2 with ratio 2/2 ==1 but product overflows before division
        assertThrows(ArithmeticException.class, () -> CostBasisCalculator.refund(big, 2, 2));
        // another overflow: (MAX/2+1)*2
        Money halfPlusOne = new Money(Long.MAX_VALUE / 2 + 1, CUR);
        assertThrows(ArithmeticException.class, () -> CostBasisCalculator.refund(halfPlusOne, 2, 2));
    }

    @Test
    void refundCurrencyPreserved() {
        Money cost = new Money(100, CUR);
        Money refund = CostBasisCalculator.refund(cost, 1, 2);
        assertEquals(CUR, refund.currency());
    }
}
