package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.ArrayList;
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
 * <p>Land bindings and the land default arrive through the durable land
 * authorisation snapshot: a trusted actor's binding answers above the land
 * default, and strangers fall through to the configured world/global
 * defaults and then to the resolver's implicit {@code DENY}. Only the
 * actor's own bindings are carried, and only for whitelisted subject
 * actions; rule actions never observe the land layers here. Any lookup
 * failure degrades to an empty grant (fail-closed) instead of assuming
 * access.
 *
 * <p>SubLand layers are not carried: they stay {@code INHERIT} until subland
 * authorisation data is stored.
 */
public final class ConfigSubjectPermissionLookup implements SubjectPermissionLookup {

    private final Supplier<PermissionDefaultsSnapshot> snapshots;
    private final Supplier<LandAuthorisationSnapshot> landAuthorisations;

    /**
     * @param snapshots live snapshot source (typically a volatile read);
     *                  {@code null} or a throwing/{@code null}-returning
     *                  source reads as an empty snapshot (fail-closed)
     */
    public ConfigSubjectPermissionLookup(Supplier<PermissionDefaultsSnapshot> snapshots) {
        this(snapshots, LandAuthorisationSnapshot::empty);
    }

    /**
     * @param snapshots live config-defaults source (typically a volatile read)
     * @param landAuthorisations live durable land-layer source (typically a
     *                  volatile read); {@code null} or a throwing/
     *                  {@code null}-returning source reads as empty (fail-closed)
     */
    public ConfigSubjectPermissionLookup(Supplier<PermissionDefaultsSnapshot> snapshots,
            Supplier<LandAuthorisationSnapshot> landAuthorisations) {
        this.snapshots = snapshots != null ? snapshots : PermissionDefaultsSnapshot::empty;
        this.landAuthorisations = landAuthorisations != null
                ? landAuthorisations : LandAuthorisationSnapshot::empty;
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
            LandAuthorisationSnapshot landAuth = landAuthorisations.get();
            if (landAuth == null) {
                landAuth = LandAuthorisationSnapshot.empty();
            }
            boolean ruleAction = action.decisionSource() == DecisionSource.LAND_RULE;
            return new Grant(
                    ruleAction ? List.of() : bindingsFor(actor, landId, landAuth),
                    ruleAction ? PermissionState.INHERIT : landAuth.landDefault(landId, action),
                    defaults.subjectWorldDefault(land.worldId(), action),
                    defaults.subjectGlobalDefault(action));
        } catch (RuntimeException failure) {
            return Grant.empty();
        }
    }

    private static List<PermissionBinding> bindingsFor(
            UUID actor, LandId landId, LandAuthorisationSnapshot landAuth) {
        List<PermissionBinding> bindings = new ArrayList<>();
        for (ProtectionActionType granted : landAuth.directAllows(actor, landId)) {
            if (!DirectTrustWhitelist.isAllowed(granted)) {
                continue;
            }
            bindings.add(new PermissionBinding(
                    PermissionSubject.player(actor), new Permission(granted, PermissionState.ALLOW)));
        }
        return List.copyOf(bindings);
    }
}
