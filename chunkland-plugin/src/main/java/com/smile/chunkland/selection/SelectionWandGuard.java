package com.smile.chunkland.selection;

import com.smile.chunkland.api.land.LandId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure, memory-only wand guard that classifies a first selection corner.
 *
 * <p>The first corner decides the selection's context: a wilderness chunk
 * opens a new claim ({@code CREATE_LAND}), the actor's own Player Land opens
 * an edit session targeting that land ({@code EDIT_SELECTION}), and any other
 * land — another player's or a Server Land — is blocked. The blocked verdict
 * never exposes the owner: the caller renders a generic message.
 *
 * <p>The later candidate rectangle is still checked by
 * {@link SelectionEditService#selectRectangle}, whose collision guard already
 * allows the session target plus wilderness and rejects every other land. This
 * class only supplies the ownership-aware first-point decision that the packed
 * {@link SelectionLandIndex} cannot answer.
 *
 * <p>Reads go through {@link SelectionLandLookup} only; no World, Chunk, SQL or
 * network access happens here.
 */
public final class SelectionWandGuard {

    /** What a first wand click means for the session it would open. */
    public enum FirstPointKind {
        CREATE_LAND,
        EDIT_SELECTION,
        BLOCKED
    }

    /**
     * Immutable first-point decision: the session mode, the optional edit
     * target with its structure revision captured from the same snapshot, and
     * the occupied land to preview when the first point is blocked.
     */
    public record FirstPoint(
            FirstPointKind kind,
            Optional<LandId> targetLandId,
            long baseStructureRevision,
            Optional<LandId> occupiedLandId) {
        public FirstPoint {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetLandId, "targetLandId");
            Objects.requireNonNull(occupiedLandId, "occupiedLandId");
            if (baseStructureRevision < 0) {
                throw new IllegalArgumentException("baseStructureRevision must be non-negative");
            }
        }

        public static FirstPoint createLand() {
            return new FirstPoint(FirstPointKind.CREATE_LAND, Optional.empty(), 0L, Optional.empty());
        }

        public static FirstPoint editSelection(LandId targetLandId, long baseStructureRevision) {
            Objects.requireNonNull(targetLandId, "targetLandId");
            return new FirstPoint(
                    FirstPointKind.EDIT_SELECTION, Optional.of(targetLandId), baseStructureRevision, Optional.empty());
        }

        public static FirstPoint blocked(LandId occupiedLandId) {
            Objects.requireNonNull(occupiedLandId, "occupiedLandId");
            return new FirstPoint(FirstPointKind.BLOCKED, Optional.empty(), 0L, Optional.of(occupiedLandId));
        }
    }

    private final SelectionLandLookup lookup;

    public SelectionWandGuard(SelectionLandLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Classify the first corner under the actor's ownership. A failing lookup
     * propagates so the caller rejects the click instead of selecting blindly.
     */
    public FirstPoint classifyFirstPoint(UUID actor, SelectionPoint point) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(point, "point");
        SelectionLandContext context = lookup.landAt(
                point.worldId(), Math.floorDiv(point.blockX(), 16), Math.floorDiv(point.blockZ(), 16));
        if (context == null) {
            return FirstPoint.createLand();
        }
        if (context.ownedBy(actor)) {
            return FirstPoint.editSelection(context.landId(), context.structureRevision());
        }
        return FirstPoint.blocked(context.landId());
    }
}
