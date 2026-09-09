package com.smile.chunkland.subland;

/**
 * Fail-closed signal that a SubLand candidate extends below the parent's
 * effective protection floor and needs an explicit operator confirmation
 * before the parent stored depth may be extended.
 *
 * <p>Never thrown speculatively: the domain service throws it only after the
 * geometry, containment, overlap and limit checks have passed, so callers can
 * prompt for confirmation without revalidating the rest. Nothing is persisted
 * and the parent depth is never modified on this path.
 */
public final class DepthExtendConfirmationRequired extends IllegalStateException {

    private final int effectiveMinY;
    private final int requestedMinY;

    public DepthExtendConfirmationRequired(int effectiveMinY, int requestedMinY) {
        super("SubLand minY (" + requestedMinY + ") is below the parent effective floor ("
                + effectiveMinY + "): explicit depth-extend confirmation is required");
        this.effectiveMinY = effectiveMinY;
        this.requestedMinY = requestedMinY;
    }

    /** Parent effective minimum protected Y that the candidate would breach. */
    public int effectiveMinY() {
        return effectiveMinY;
    }

    /** Candidate cuboid minimum Y that needs confirmation. */
    public int requestedMinY() {
        return requestedMinY;
    }
}
