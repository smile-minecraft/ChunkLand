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
 * actions; rule actions never observe the land layers here. A per-land
 * ENTRY ban adds one player-scoped ENTRY DENY to the same aggregation
 * layer, so the existing DENY-first precedence denies banned players
 * without touching their trust profile. An unloaded land snapshot (cache
 * startup before the first durable load, or a failed reload) denies every
 * subject-permission action at the land layer, so world/global defaults
 * can never admit a stranger while bans are unverifiable; land-rule
 * actions stay on their own chain and are never touched by ban storage.
 * Any lookup failure degrades to an empty grant (fail-closed) instead of
 * assuming access.
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
        this(snapshots, LandAuthorisationSnapshot::unloaded);
    }

    /**
     * @param snapshots live config-defaults source (typically a volatile read)
     * @param landAuthorisations live durable land-layer source (typically a
     *                  volatile read); {@code null} or a throwing/
     *                  {@code null}-returning source reads as unloaded (fail-closed)
     */
    public ConfigSubjectPermissionLookup(Supplier<PermissionDefaultsSnapshot> snapshots,
            Supplier<LandAuthorisationSnapshot> landAuthorisations) {
        this.snapshots = snapshots != null ? snapshots : PermissionDefaultsSnapshot::empty;
        this.landAuthorisations = landAuthorisations != null
                ? landAuthorisations : LandAuthorisationSnapshot::unloaded;
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
            LandAuthorisationSnapshot landAuth;
            try {
                landAuth = landAuthorisations.get();
            } catch (RuntimeException failure) {
                landAuth = null;
            }
            if (landAuth == null) {
                landAuth = LandAuthorisationSnapshot.unloaded();
            }
            boolean ruleAction = action.decisionSource() == DecisionSource.LAND_RULE;
            if (!landAuth.loaded() && !ruleAction) {
                // Bans are unverifiable while unloaded: deny at the land
                // layer so the world/global subject defaults below can never
                // admit anyone. Land-rule actions skip this entirely and keep
                // their own chain below.
                return new Grant(List.of(), PermissionState.DENY,
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new Grant(
                    ruleAction ? List.of() : bindingsFor(actor, landId, action, landAuth),
                    ruleAction ? PermissionState.INHERIT : landAuth.landDefault(landId, action),
                    defaults.subjectWorldDefault(land.worldId(), action),
                    defaults.subjectGlobalDefault(action));
        } catch (RuntimeException failure) {
            return Grant.empty();
        }
    }

    private static List<PermissionBinding> bindingsFor(
            UUID actor, LandId landId, ProtectionActionType action,
            LandAuthorisationSnapshot landAuth) {
        List<PermissionBinding> bindings = new ArrayList<>();
        for (ProtectionActionType granted : landAuth.directAllows(actor, landId)) {
            if (!DirectTrustWhitelist.isAllowed(granted)) {
                continue;
            }
            bindings.add(new PermissionBinding(
                    PermissionSubject.player(actor), new Permission(granted, PermissionState.ALLOW)));
        }
        // Per-land ENTRY bans deny through the same aggregation layer: the
        // existing flat DENY-first precedence lets this DENY win over the
        // direct trust ALLOW above without rewriting any profile row. Only
        // ENTRY is ever denied here; other actions keep their trust grants
        // and rule actions never observe this layer at all.
        if (action == ProtectionActionType.ENTRY && landAuth.isBanned(actor, landId)) {
            bindings.add(new PermissionBinding(
                    PermissionSubject.player(actor),
                    new Permission(ProtectionActionType.ENTRY, PermissionState.DENY)));
        }
        return List.copyOf(bindings);
    }
}
