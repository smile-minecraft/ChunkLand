package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.api.land.ChunkKey;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production runtime accumulator for concurrent depth proposals
 * ({@code storedMinProtectedY = min(current, requested)}).
 *
 * <p>State machine per chunk: the cell holds the deepest depth accepted so
 * far. Both {@link #seed} (durable value loaded at startup) and
 * {@link #propose} (runtime operation) fold through the same atomic minimum,
 * so the merge is commutative, associative and idempotent — proposals
 * converge to the global minimum regardless of arrival order, and a
 * shallower proposal can never overwrite an accepted deeper value.
 * No lost update: every {@code propose} returns the minimum observed at
 * its linearization point.
 *
 * <p>Lock-free: each cell is an {@link AtomicInteger} updated with a CAS
 * loop ({@code accumulateAndGet}). This class creates no threads and
 * performs no I/O; draining to durable storage is the persistence queue's
 * job. Depth never participates in structure or policy revisions, so
 * updates here invalidate no caches.
 */
public final class DepthCasAccumulator {

    private final ConcurrentHashMap<ChunkKey, AtomicInteger> minima = new ConcurrentHashMap<>();

    /**
     * Fold the durable stored depth into the cell (startup or rebuild path).
     * Uses the same atomic minimum as {@link #propose}, so racing a seed
     * against proposals still converges to the deepest value.
     *
     * @return the minimum after folding
     */
    public int seed(ChunkKey chunk, int storedDepth) {
        Objects.requireNonNull(chunk, "chunk");
        return minima.computeIfAbsent(chunk, ignored -> new AtomicInteger(storedDepth))
                .accumulateAndGet(storedDepth, Math::min);
    }

    /**
     * Propose a deeper-or-equal depth for a chunk.
     *
     * @return the accepted minimum after this proposal (never shallower than
     *         any previously accepted value for the chunk)
     */
    public int propose(ChunkKey chunk, int requestedDepth) {
        Objects.requireNonNull(chunk, "chunk");
        return minima.computeIfAbsent(chunk, ignored -> new AtomicInteger(requestedDepth))
                .accumulateAndGet(requestedDepth, Math::min);
    }

    /** Current accepted minimum, or empty when nothing was proposed or seeded. */
    public Optional<Integer> current(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        AtomicInteger cell = minima.get(chunk);
        return cell == null ? Optional.empty() : Optional.of(cell.get());
    }

    /** Immutable snapshot of all accepted minima. */
    public Map<ChunkKey, Integer> snapshot() {
        Map<ChunkKey, Integer> out = new ConcurrentHashMap<>(minima.size());
        minima.forEach((chunk, cell) -> out.put(chunk, cell.get()));
        return Map.copyOf(out);
    }
}
