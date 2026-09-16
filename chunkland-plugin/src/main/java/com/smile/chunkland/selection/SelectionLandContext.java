package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, memory-only classification of the land occupying one chunk.
 *
 * <p>Produced by {@link SelectionLandLookup} from an already-built immutable
 * LandRegistry snapshot. It carries the land id, its owner reference and its
 * structure revision so the wand guard can tell the actor's own Player Land
 * from another player's or a Server Land. No Bukkit world, chunk, SQL row or
 * network handle is retained.
 */
public record SelectionLandContext(LandId landId, OwnerRef ownerRef, long structureRevision) {

    public SelectionLandContext {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(ownerRef, "ownerRef");
        if (structureRevision < 0) {
            throw new IllegalArgumentException("structureRevision must be non-negative");
        }
    }

    /** Whether the given actor owns this land as a player. */
    public boolean ownedBy(UUID actor) {
        Objects.requireNonNull(actor, "actor");
        return ownerRef instanceof OwnerRef.PlayerOwnerRef player && player.uuid().equals(actor);
    }
}
