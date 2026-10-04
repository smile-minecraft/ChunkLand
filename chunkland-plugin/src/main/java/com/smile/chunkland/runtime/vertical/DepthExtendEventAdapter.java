package com.smile.chunkland.runtime.vertical;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Production trigger adapter from block operations to depth-extend proposals.
 *
 * <p>The adapter never touches a Bukkit {@code World}, {@code Chunk},
 * {@code Block} or entity: the caller supplies the already-known world UUID,
 * chunk and operation Y from the event, plus the pre-resolved authorization
 * and the immutable runtime snapshot seams. Land membership and the stored
 * depth resolve from those injected seams only, so no world data is queried
 * here and no SQL or I/O runs on the hot path.
 *
 * <p>Only {@link AutoExtendTrigger#BLOCK_BREAK} and
 * {@link AutoExtendTrigger#BLOCK_PLACE} with an authorized actor inside a
 * land may propose; every other trigger kind, unauthorized attempt,
 * wilderness position and shallower-than-stored operation returns empty and
 * never reaches the persistence queue.
 */
public final class DepthExtendEventAdapter {

    /** Resolves the owning land for event coordinates, or null for wilderness. */
    @FunctionalInterface
    public interface LandLookup {
        LandId findLandId(UUID worldId, int chunkX, int chunkZ);
    }

    /**
     * Resolves the authoritative stored depth for a claimed chunk.
     * Empty only for wilderness; claimed chunks without a durable row resolve
     * through the legacy fallback inside this adapter.
     */
    @FunctionalInterface
    public interface StoredDepthLookup extends BiFunction<ChunkKey, LandId, Optional<Integer>> {
    }

    private final LandLookup landLookup;
    private final StoredDepthLookup storedLookup;
    private final int buffer;
    private final int worldMinHeight;

    /**
     * @param landLookup immutable-snapshot land membership; never null
     * @param storedLookup immutable-snapshot stored depths; never null
     * @param buffer non-negative auto-extend buffer subtracted from the operation plane
     * @param worldMinHeight inclusive world minimum (lower clamp)
     */
    public DepthExtendEventAdapter(LandLookup landLookup, StoredDepthLookup storedLookup,
            int buffer, int worldMinHeight) {
        this.landLookup = Objects.requireNonNull(landLookup, "landLookup");
        this.storedLookup = Objects.requireNonNull(storedLookup, "storedLookup");
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer must be >= 0: " + buffer);
        }
        this.buffer = buffer;
        this.worldMinHeight = worldMinHeight;
    }

    /**
     * Classify one block operation and, when it legally extends protection,
     * build the queue-ready request.
     *
     * @param trigger already-classified operation kind
     * @param actor triggering player; null only when unknown
     * @param actorAuthorized owner or matching-grant holder, resolved by the caller
     * @param chunk event chunk (world UUID plus chunk coordinates, no world lookup)
     * @param operationY event block Y
     * @return the proposal to offer to the persistence queue, or empty when silent
     */
    public Optional<DepthExtendRequest> propose(AutoExtendTrigger trigger, UUID actor,
            boolean actorAuthorized, ChunkKey chunk, int operationY) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(chunk, "chunk");
        if (!trigger.triggersExtend()) {
            return Optional.empty();
        }
        if (!actorAuthorized) {
            return Optional.empty();
        }
        LandId landId = landLookup.findLandId(chunk.worldId(), chunk.chunkX(), chunk.chunkZ());
        if (landId == null) {
            return Optional.empty();
        }
        int stored;
        try {
            stored = storedLookup.apply(chunk, landId)
                    .orElse(VerticalDepths.LEGACY_STORED_FALLBACK_Y);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        return AutoExtendDecision.decide(trigger, true, true, stored, operationY, buffer, worldMinHeight)
                .map(requested -> new DepthExtendRequest(chunk, landId, actor, requested, operationY));
    }
}
