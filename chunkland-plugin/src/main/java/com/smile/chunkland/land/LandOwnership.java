package com.smile.chunkland.land;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.Objects;

/**
 * Ownership transfer rules.
 *
 * <p>V1 Server Land is not transferable. Player Land transfer is not specified for V1
 * and is therefore left to future sagas. This class centralises the rule so
 * {@link com.smile.chunkland.api.land.LandSnapshot#withOwner(OwnerRef)} remains a
 * low-level structural copy while the business invariant is enforced at the
 * service/persistence boundary without misusing a special UUID or transferable owner.</p>
 */
public final class LandOwnership {

    private LandOwnership() {}

    /**
     * Validate that a transfer is allowed. Throws {@link IllegalStateException}
     * if the current snapshot is a Server Land.
     *
     * @param current current land snapshot (must not be null)
     * @param newOwner proposed new owner (must not be null)
     */
    public static void validateTransfer(LandSnapshot current, OwnerRef newOwner) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(newOwner, "newOwner");
        if (current.ownerRef() instanceof OwnerRef.ServerOwnerRef
                && !(newOwner instanceof OwnerRef.ServerOwnerRef)) {
            throw new IllegalStateException("Server Land is not transferable");
        }
        // Player Land transfer is not enabled in V1; no additional check here to avoid
        // prematurely locking future GUILD etc. Future tasks will extend this method.
    }

    public static boolean isServerLand(LandSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.ownerRef() instanceof OwnerRef.ServerOwnerRef;
    }
}
