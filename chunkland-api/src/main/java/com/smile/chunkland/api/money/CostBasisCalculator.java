package com.smile.chunkland.api.money;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure allocation and refund math for cost basis (spec §50).
 *
 * <p>All calculations use exact integer arithmetic on minor units. No floating-point
 * arithmetic is used.
 *
 * <h3>Allocation contract</h3>
 * <ul>
 *   <li>Total must be non-negative; chunk collection must be non-empty and duplicate-free.</li>
 *   <li>Remainder {@code total % n} is distributed one minor unit at a time to the first
 *       {@code remainder} chunks in deterministic {@code (chunkX, chunkZ)} order.</li>
 *   <li>Sum of allocations equals total exactly.</li>
 * </ul>
 *
 * <h3>Refund contract</h3>
 * <ul>
 *   <li>Refund is defined strictly as {@code originalCostBasis * numerator / denominator}
 *       with no re-pricing via {@link PricingTable} and no current tier lookup.</li>
 *   <li>Ratio is an exact rational {@code numerator/denominator} bounded to a non-negative
 *       fraction at or below one: {@code 0 <= numerator <= denominator}, denominator
 *       {@code >0}. This enforces the product decision that refund cannot exceed the
 *       original cost basis; no repository/spec evidence supports a ratio above one, so
 *       such ratios are rejected as invalid rather than silently refunding extra.</li>
 *   <li>Rounding is half-up to the nearest minor unit: fractional {@code >=0.5} rounds up.
 *       For positive values this is {@code (product + denominator/2) / denominator} with tie up,
 *       implemented via remainder vs threshold to avoid overflow.</li>
 *   <li>Overflow on {@code costBasis * numerator} throws {@link ArithmeticException}.</li>
 * </ul>
 */
public final class CostBasisCalculator {

    private CostBasisCalculator() {
    }

    /**
     * Allocate the lot total across the given chunks.
     *
     * @param total  total lot price, must be non-null and non-negative
     * @param chunks chunk coordinates, must be non-null, non-empty, duplicate-free, no null elements
     * @return immutable allocation where sum equals total
     * @throws NullPointerException     if total or chunks is null, or any element is null
     * @throws IllegalArgumentException if total is negative, chunks empty, or duplicate chunk
     */
    public static CostBasisAllocation allocate(Money total, Collection<ChunkCoordinate> chunks) {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(chunks, "chunks");
        if (total.isNegative()) {
            throw new IllegalArgumentException("total must not be negative, got " + total);
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        // Defensive copy and null/duplicate checks
        List<ChunkCoordinate> copy = new ArrayList<>(chunks.size());
        Set<ChunkCoordinate> seen = new HashSet<>(chunks.size() * 2);
        for (ChunkCoordinate c : chunks) {
            Objects.requireNonNull(c, "chunk element must not be null");
            if (!seen.add(c)) {
                throw new IllegalArgumentException("duplicate chunk: " + c);
            }
            copy.add(c);
        }
        // Deterministic order
        copy.sort(null); // uses ChunkCoordinate.compareTo

        int n = copy.size();
        long totalMinor = total.minorUnits();
        long quotient = totalMinor / n;
        long remainder = totalMinor % n; // 0 <= remainder < n for non-negative total

        Map<ChunkCoordinate, Money> map = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            long minor = quotient + (i < remainder ? 1 : 0);
            map.put(copy.get(i), new Money(minor, total.currency()));
        }
        // Verify conservation (defensive, should always hold)
        long sum = 0L;
        for (Money m : map.values()) {
            sum = Math.addExact(sum, m.minorUnits());
        }
        if (sum != totalMinor) {
            throw new IllegalStateException("allocation sum " + sum + " != total " + totalMinor);
        }
        Map<ChunkCoordinate, Money> unmodifiable = Collections.unmodifiableMap(map);
        return new CostBasisAllocation(total, unmodifiable);
    }

    /**
     * Convenience overload for Set input.
     */
    public static CostBasisAllocation allocate(Money total, Set<ChunkCoordinate> chunks) {
        return allocate(total, (Collection<ChunkCoordinate>) chunks);
    }

    /**
     * Refund with default ratio 1/2.
     */
    public static Money refund(Money costBasis) {
        return refund(costBasis, 1, 2);
    }

    /**
     * Refund as {@code costBasis * numerator / denominator} with half-up rounding.
     *
     * @param costBasis  original per-chunk cost basis, must be non-null and non-negative
     * @param numerator  ratio numerator, must be 0 <= numerator <= denominator
     * @param denominator ratio denominator, must be >0
     * @return refund amount in same currency
     * @throws NullPointerException     if costBasis is null
     * @throws IllegalArgumentException if costBasis negative, numerator negative, denominator <=0,
     *                                  or numerator > denominator (ratio above one)
     * @throws ArithmeticException      on overflow
     */
    public static Money refund(Money costBasis, long numerator, long denominator) {
        Objects.requireNonNull(costBasis, "costBasis");
        if (costBasis.isNegative()) {
            throw new IllegalArgumentException("costBasis must not be negative, got " + costBasis);
        }
        if (numerator < 0) {
            throw new IllegalArgumentException("numerator must be >=0, got " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be >0, got " + denominator);
        }
        if (numerator > denominator) {
            throw new IllegalArgumentException(
                    "refund ratio must be at or below one (numerator <= denominator), got "
                            + numerator + "/" + denominator);
        }
        long product = Math.multiplyExact(costBasis.minorUnits(), numerator);
        long quotient = product / denominator;
        long remainder = product % denominator;
        // half-up threshold = ceil(denominator/2) = denominator/2 + denominator%2
        long threshold = denominator / 2 + denominator % 2;
        if (remainder >= threshold) {
            quotient = Math.incrementExact(quotient);
        }
        return new Money(quotient, costBasis.currency());
    }
}
