package com.smile.chunkland.api.money;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-chunk cost basis allocation for a single claim lot (spec §50).
 *
 * <p>The total price of the lot is distributed as minor-unit values that sum exactly
 * to the original total. The mapping is immutable and defensively copied; the
 * allocation order is deterministic by {@code (chunkX, chunkZ)}.
 */
public final class CostBasisAllocation {

    private final Money total;
    private final Map<ChunkCoordinate, Money> allocations;

    CostBasisAllocation(Money total, Map<ChunkCoordinate, Money> allocations) {
        this.total = Objects.requireNonNull(total, "total");
        Objects.requireNonNull(allocations, "allocations");
        // Defensive copy: callers may pass a mutable map (e.g. LinkedHashMap). The allocation
        // must remain immutable and iteration order must be preserved without depending on caller.
        this.allocations = Collections.unmodifiableMap(new LinkedHashMap<>(allocations));
    }

    /** Total price of the lot. */
    public Money total() {
        return total;
    }

    /** Currency of the allocation (same as total). */
    public Currency currency() {
        return total.currency();
    }

    /** Number of chunks in the lot. */
    public int size() {
        return allocations.size();
    }

    /**
     * Immutable view of the per-chunk cost basis mapping.
     *
     * <p>The map is unmodifiable and iteration order is the deterministic
     * {@code (chunkX, chunkZ)} order.
     */
    public Map<ChunkCoordinate, Money> allocations() {
        return allocations;
    }

    /**
     * Cost basis for a specific chunk.
     *
     * @throws IllegalArgumentException if the chunk is not part of this allocation
     */
    public Money costBasisFor(ChunkCoordinate chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Money m = allocations.get(chunk);
        if (m == null) {
            throw new IllegalArgumentException("chunk not in allocation: " + chunk);
        }
        return m;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CostBasisAllocation other)) {
            return false;
        }
        return total.equals(other.total) && allocations.equals(other.allocations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, allocations);
    }

    @Override
    public String toString() {
        return "CostBasisAllocation{total=" + total + ", size=" + allocations.size() + "}";
    }
}
