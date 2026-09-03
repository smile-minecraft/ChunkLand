package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
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
     * Subject layers the provider copies into the decision context. SubLand
     * layers stay {@code INHERIT} until subland authorisation data is stored;
     * only Land-level and above are carried here.
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

    /** Lookup that grants nothing; every subject layer falls through to implicit DENY. */
    static SubjectPermissionLookup empty() {
        return (actor, landId, action, snapshot) -> Grant.empty();
    }

    /** Fail-fast guard for providers that require a real lookup to be present. */
    static Grant requireNonNullGrant(Grant grant) {
        return Objects.requireNonNull(grant, "subject lookup returned no grants");
    }
}
