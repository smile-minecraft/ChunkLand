package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Config-backed {@link SubjectPermissionLookup} for the world/global subject
 * default layers.
 *
 * <p>Each call reads one volatile {@link PermissionDefaultsSnapshot} and
 * answers from its immutable UUID-keyed maps, so a config reload applies to
 * new decisions without touching in-flight readers — and a stale snapshot
 * reference keeps answering with its own values instead of observing later
 * publishes. No Bukkit, SQL, chunk load or network access happens here.
 *
 * <p>Composition seam (honest, not finished direct trust): land bindings and
 * the land default have no durable source yet, so they stay empty/
 * {@code INHERIT} here. With no bindings, strangers correctly fall through
 * to the configured world/global defaults and then to the resolver's
 * implicit {@code DENY}. Any lookup failure degrades to an empty grant
 * (fail-closed) instead of assuming access.
 *
 * <p>SubLand layers are not carried: they stay {@code INHERIT} until subland
 * authorisation data is stored.
 */
public final class ConfigSubjectPermissionLookup implements SubjectPermissionLookup {

    private final Supplier<PermissionDefaultsSnapshot> snapshots;

    /**
     * @param snapshots live snapshot source (typically a volatile read);
     *                  {@code null} or a throwing/{@code null}-returning
     *                  source reads as an empty snapshot (fail-closed)
     */
    public ConfigSubjectPermissionLookup(Supplier<PermissionDefaultsSnapshot> snapshots) {
        this.snapshots = snapshots != null ? snapshots : PermissionDefaultsSnapshot::empty;
    }

    @Override
    public Grant grants(UUID actor, LandId landId, ProtectionActionType action, LandRegistry snapshot) {
        try {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(landId, "landId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(snapshot, "snapshot");
            LandSnapshot land = snapshot.land(landId);
            if (land == null) {
                return Grant.empty();
            }
            PermissionDefaultsSnapshot defaults = snapshots.get();
            if (defaults == null) {
                return Grant.empty();
            }
            return new Grant(
                    List.of(),
                    PermissionState.INHERIT,
                    defaults.subjectWorldDefault(land.worldId(), action),
                    defaults.subjectGlobalDefault(action));
        } catch (RuntimeException failure) {
            return Grant.empty();
        }
    }
}
