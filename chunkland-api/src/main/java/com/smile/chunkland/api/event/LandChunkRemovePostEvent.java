package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Post event for a committed land shrink (chunk remove).
 *
 * <p>Fired exactly once, and only after the durable domain commit, a
 * <em>confirmed successful</em> Economy refund, and the runtime snapshot
 * publish all succeeded. A failed or unconfirmed refund parks the operation
 * for compensation and never fires this event. This differs from {@code
 * LandDeletePostEvent} (and the create/expand posts), which fire after the
 * commit plus the publish but before the refund or final ledger marking is
 * attempted.
 *
 * <p>Fires on the async continuation thread, never on a Folia region thread:
 * listeners must not block, must not perform I/O, must not wait, and must
 * hop through the player scheduler before touching any Bukkit/Paper player
 * or world state. A throwing listener is caught, logged and isolated — the
 * committed shrink still stands and is never rolled back. The delta set is
 * an immutable snapshot copy bounded to {@link #MAX_CHUNKS} entries.
 */
public final class LandChunkRemovePostEvent {

    /** Hard bound on the carried delta snapshot; larger commits fail closed. */
    public static final int MAX_CHUNKS = 4096;

    private final LandId landId;
    private final UUID actorUuid;
    private final Set<ChunkKey> removedChunks;
    private final long refundMinorUnits;

    /**
     * @param landId shrunk land
     * @param actorUuid shrinking player (or console actor)
     * @param removedChunks committed delta snapshot; defensively copied and bounded
     * @param refundMinorUnits refund derived from the durable cost basis, never negative
     */
    public LandChunkRemovePostEvent(LandId landId, UUID actorUuid, Set<ChunkKey> removedChunks,
            long refundMinorUnits) {
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(removedChunks, "removedChunks");
        if (refundMinorUnits < 0) {
            throw new IllegalArgumentException("refundMinorUnits must not be negative");
        }
        if (removedChunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("removedChunks exceed " + MAX_CHUNKS);
        }
        for (ChunkKey chunk : removedChunks) {
            Objects.requireNonNull(chunk, "chunk");
        }
        this.removedChunks = Set.copyOf(removedChunks);
        this.refundMinorUnits = refundMinorUnits;
    }

    /** Shrunk land. */
    public LandId landId() {
        return landId;
    }

    /** Shrinking player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** Immutable, bounded snapshot of the committed delta. */
    public Set<ChunkKey> removedChunks() {
        return removedChunks;
    }

    /** Refund derived from the durable cost basis. */
    public long refundMinorUnits() {
        return refundMinorUnits;
    }
}
