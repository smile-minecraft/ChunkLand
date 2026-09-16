package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cancellable Pre event for a land expansion (chunk add).
 *
 * <p>Fired synchronously on the mutation caller's thread after validation
 * and pricing but before chunk-quota reservations, the ledger row, and any
 * Economy charge. Cancelling (or a throwing listener, which fails closed to
 * cancelled) aborts the expansion with zero side effects: no quota movement,
 * no reservation, no ledger row, no charge, no audit.
 *
 * <p>Thread contract: same-thread, synchronous dispatch. Listeners must not
 * block, must not perform I/O, must not wait, and must not touch Bukkit/Paper
 * world state unless already on the matching Folia thread. The delta set is
 * an immutable snapshot copy bounded to {@link #MAX_CHUNKS} entries.
 */
public final class LandChunkAddPreEvent implements ChunkLandCancellable {

    /** Hard bound on the carried delta snapshot; larger plans fail closed. */
    public static final int MAX_CHUNKS = 4096;

    private final UUID actorUuid;
    private final UUID worldId;
    private final LandId targetLandId;
    private final Set<ChunkKey> chunks;
    private volatile boolean cancelled;

    /**
     * @param actorUuid expanding player (or console actor)
     * @param worldId world the land lives in
     * @param targetLandId land that would grow
     * @param chunks validated delta snapshot; defensively copied and bounded
     */
    public LandChunkAddPreEvent(UUID actorUuid, UUID worldId, LandId targetLandId,
            Set<ChunkKey> chunks) {
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.targetLandId = Objects.requireNonNull(targetLandId, "targetLandId");
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("chunks exceed " + MAX_CHUNKS);
        }
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
        }
        this.chunks = Set.copyOf(chunks);
    }

    /** Expanding player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the land lives in. */
    public UUID worldId() {
        return worldId;
    }

    /** Land that would grow. */
    public LandId targetLandId() {
        return targetLandId;
    }

    /** Immutable, bounded snapshot of the validated delta. */
    public Set<ChunkKey> chunks() {
        return chunks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
