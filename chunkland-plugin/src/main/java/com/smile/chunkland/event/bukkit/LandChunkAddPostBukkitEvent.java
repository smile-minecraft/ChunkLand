package com.smile.chunkland.event.bukkit;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Bukkit view of {@code LandChunkAddPostEvent}: the expansion committed and
 * the runtime published. Fired async; listeners must not block, must not
 * perform I/O, and must hop to the matching Folia thread before touching
 * world state. Throwing listeners are isolated and never roll back the
 * expansion.
 */
public final class LandChunkAddPostBukkitEvent extends ChunkLandBukkitEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LandId landId;
    private final UUID actorUuid;
    private final Set<ChunkKey> addedChunks;
    private final long priceMinorUnits;

    /** @param async mirrors the dispatch thread (see {@link ChunkLandBukkitEvent}) */
    public LandChunkAddPostBukkitEvent(LandId landId, UUID actorUuid, Set<ChunkKey> addedChunks,
            long priceMinorUnits, boolean async) {
        super(async);
        this.landId = Objects.requireNonNull(landId, "landId");
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(addedChunks, "addedChunks");
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

    /** Immutable snapshot of the committed delta. */
    public Set<ChunkKey> addedChunks() {
        return addedChunks;
    }

    /** Charged price in minor units. */
    public long priceMinorUnits() {
        return priceMinorUnits;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit handler list for this event type. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
