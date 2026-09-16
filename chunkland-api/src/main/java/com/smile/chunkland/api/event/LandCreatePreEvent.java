package com.smile.chunkland.api.event;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cancellable Pre event for a paid land claim.
 *
 * <p>Fired synchronously on the mutation caller's thread after validation
 * and pricing but before quota reservations, the ledger row, and any Economy
 * charge. Cancelling (or a throwing listener, which fails closed to
 * cancelled) aborts the claim with zero side effects: no quota movement, no
 * reservation, no ledger row, no charge, no audit.
 *
 * <p>Thread contract: same-thread, synchronous dispatch. Listeners must not
 * block, must not perform I/O, must not wait, and must not touch Bukkit/Paper
 * world state unless already on the matching Folia thread. The chunk set is
 * an immutable snapshot copy bounded to {@link #MAX_CHUNKS} entries; the
 * owner reference carries identity only, never credentials.
 */
public final class LandCreatePreEvent implements ChunkLandCancellable {

    /** Hard bound on the carried chunk snapshot; larger plans fail closed. */
    public static final int MAX_CHUNKS = 4096;

    private final UUID actorUuid;
    private final UUID worldId;
    private final OwnerRef owner;
    private final Set<ChunkKey> chunks;
    private final String displayName;
    private volatile boolean cancelled;

    /**
     * @param actorUuid claiming player (or console actor)
     * @param worldId world the claim lands in
     * @param owner durable owner the land would belong to
     * @param chunks validated chunk snapshot; defensively copied and bounded
     * @param displayName requested land name, never blank
     */
    public LandCreatePreEvent(UUID actorUuid, UUID worldId, OwnerRef owner,
            Set<ChunkKey> chunks, String displayName) {
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("chunks exceed " + MAX_CHUNKS);
        }
        for (ChunkKey chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
        }
        this.chunks = Set.copyOf(chunks);
        this.displayName = displayName;
    }

    /** Claiming player (or console actor). */
    public UUID actorUuid() {
        return actorUuid;
    }

    /** World the claim would land in. */
    public UUID worldId() {
        return worldId;
    }

    /** Durable owner the land would belong to. */
    public OwnerRef owner() {
        return owner;
    }

    /** Immutable, bounded snapshot of the validated chunks. */
    public Set<ChunkKey> chunks() {
        return chunks;
    }

    /** Requested land name. */
    public String displayName() {
        return displayName;
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
