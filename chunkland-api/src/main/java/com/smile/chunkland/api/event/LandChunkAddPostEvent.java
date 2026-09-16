package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Post event for a committed land expansion (chunk add).
 *
 * <p>Fired exactly once, after the durable domain commit plus the runtime
 * snapshot publish both succeeded. Fires on the async continuation thread,
 * never on a Folia region thread: listeners must not block, must not perform
 * I/O, must not wait, and must hop through the player scheduler before
 * touching any Bukkit/Paper player or world state. A throwing listener is
 * caught, logged and isolated — the committed expansion still stands and is
 * never rolled back. The delta set is an immutable snapshot copy bounded to
 * {@link #MAX_CHUNKS} entries.
 */
public final class LandChunkAddPostEvent {

    /** Hard bound on the carried delta snapshot; larger commits fail closed. */
    public static final int MAX_CHUNKS = 4096;

    private final LandId landId;
    private final UUID actorUuid;
    private final Set<ChunkKey> addedChunks;
    private final long priceMinorUnits;

    /**
     * @param landId expanded land
     * @param actorUuid expanding player (or console actor)
     * @param addedChunks committed delta snapshot; defensively copied and bounded
     * @param priceMinorUnits charged price, never negative
     */
    public LandChunkAddPostEvent(LandId landId, UUID actorUuid, Set<ChunkKey> addedChunks,
            long priceMinorUnits) {
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(addedChunks, "addedChunks");
        if (priceMinorUnits < 0) {
            throw new IllegalArgumentException("priceMinorUnits must not be negative");
        }
        if (addedChunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("addedChunks exceed " + MAX_CHUNKS);
        }
        for (ChunkKey chunk : addedChunks) {
            Objects.requireNonNull(chunk, "chunk");
        }
        this.addedChunks = Set.copyOf(addedChunks);
        this.priceMinorUnits = priceMinorUnits;
    }

    /** Expanded land. */
    public LandId landId() {
        return landId;
    }

    /** Expanding player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Immutable, bounded snapshot of the committed delta. */
    public Set<ChunkKey> addedChunks() {
        return addedChunks;
    }

    /** Charged price in minor units. */
    public long priceMinorUnits() {
        return priceMinorUnits;
    }
}
