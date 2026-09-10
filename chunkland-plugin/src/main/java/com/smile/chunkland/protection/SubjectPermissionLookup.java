package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Memory-only lookup for the subject-permission layers of one decision.
 *
 * <p>The implementation reads only from the given already-published immutable
 * {@link LandRegistry} snapshot and must never perform I/O, load a chunk, or
 * touch Bukkit/SQL. A {@code null} grant, binding list, or default is treated
 * as empty/{@code INHERIT} by {@link Grant}, so missing data falls through to
 * the resolver's implicit {@code DENY} (fail-closed).
 */
@FunctionalInterface
public interface SubjectPermissionLookup {
    Grant grants(UUID actor, LandId landId, ProtectionActionType action, LandRegistry snapshot);

    /**
     * SubLand subject layers for one position-resolved subland. The default
     * implementation carries nothing, so lookups without subland data keep
     * the subland layers at {@code INHERIT} and decisions fall through to
     * the land layers below.
     *
     * @param sublandId the covering subland, or {@code null} when no subland
     *                  covers the position (which also reads as empty)
     */
    default SublandGrant sublandGrants(UUID actor, LandId landId, SubLandId sublandId,
            ProtectionActionType action, LandRegistry snapshot) {
        return SublandGrant.empty();
    }

    /**
     * Subject layers the provider copies into the decision context. The land
     * bindings now mix the actor's own direct grants with their visible
     * generic profile bindings (player rows for their own subject, group
     * rows for members only); rule actions still observe none of them.
     */
    record Grant(
            List<PermissionBinding> landBindings,
            PermissionState landDefault,
            PermissionState worldDefault,
            PermissionState globalDefault) {

        public Grant {
            landBindings = landBindings == null ? List.of() : List.copyOf(landBindings);
            landDefault = landDefault == null ? PermissionState.INHERIT : landDefault;
            worldDefault = worldDefault == null ? PermissionState.INHERIT : worldDefault;
            globalDefault = globalDefault == null ? PermissionState.INHERIT : globalDefault;
        }

        static Grant empty() {
            return new Grant(List.of(), PermissionState.INHERIT,
                    PermissionState.INHERIT, PermissionState.INHERIT);
        }
    }

    /**
     * SubLand subject layers the provider copies into the decision context
     * ahead of the land layers, so the resolver's spatial precedence applies:
     * an explicit subland value decides before any land value, and an empty
     * subland grant falls back to the land chain.
     */
    record SublandGrant(
            List<PermissionBinding> bindings,
            PermissionState sublandDefault) {

        public SublandGrant {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            sublandDefault = sublandDefault == null ? PermissionState.INHERIT : sublandDefault;
        }

        static SublandGrant empty() {
            return new SublandGrant(List.of(), PermissionState.INHERIT);
        }
    }

    /** Lookup that grants nothing; every subject layer falls through to implicit DENY. */
    static SubjectPermissionLookup empty() {
        return (actor, landId, action, snapshot) -> Grant.empty();
    }

    /** Fail-fast guard for providers that require a real lookup to be present. */
    static Grant requireNonNullGrant(Grant grant) {
        return Objects.requireNonNull(grant, "subject lookup returned no grants");
    }
}
