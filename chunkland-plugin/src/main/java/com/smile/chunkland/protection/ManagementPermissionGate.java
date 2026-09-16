package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single domain enforcement point for Land / SubLand management operations.
 *
 * <p>Every management entry point — {@code /land} subcommands today, GUI/Form
 * flows when they arrive — resolves its decision through {@link #check} before
 * touching the mutation pipeline. Callers must never submit a management
 * mutation on the strength of a Bukkit command node alone: the node only
 * controls who may <em>try</em> the subcommand, while this gate decides who
 * may <em>change</em> the land.
 *
 * <p>The gate is deliberately Bukkit-free (no Bukkit imports, no Bukkit types
 * in signatures) so server-thread Form code can share it without a server.
 * Adapters resolve the Bukkit-dependent inputs outside the gate:
 * {@code adminBypass} from the admin-bypass state and {@code serverLandSteward}
 * from the {@code chunkland.admin.serverland} node.
 *
 * <p>Operation mapping: member changes resolve {@code MANAGE_MEMBER},
 * binding/default/profile changes resolve {@code MANAGE_PERMISSION}, SubLand
 * CRUD resolves {@code MANAGE_SUBLAND}, expansion and shrink/unclaim resolve
 * {@code EXPAND_LAND}, and deletion resolves {@code DELETE_LAND}. All five are
 * {@code SUBJECT_PERMISSION}, so the Owner Guarantee applies and the owner
 * always passes without Admin Bypass.
 *
 * <p>Server Land has no player owner, so the Owner Guarantee can never fire
 * for it through the normal chain. Instead, an actor flagged as
 * {@code serverLandSteward} on a Server Land is granted the same
 * owner-equivalent ALLOW without needing Admin Bypass. The flag is ignored on
 * player-owned Land and on unknown Land, and a steward flag alone never
 * authorises anything else. Conversely, Server Land without the steward
 * flag is denied before the resolver chain is consulted, so a binding,
 * default or explicit grant scoped to a player namespace can never
 * authorise a Server Land mutation: the two namespaces stay disjoint.
 */
public final class ManagementPermissionGate {

    /** The five management actions this gate enforces. */
    public static final Set<ProtectionActionType> MANAGEMENT_ACTIONS = Set.of(
            ProtectionActionType.MANAGE_MEMBER,
            ProtectionActionType.MANAGE_PERMISSION,
            ProtectionActionType.MANAGE_SUBLAND,
            ProtectionActionType.EXPAND_LAND,
            ProtectionActionType.DELETE_LAND);

    private static final Set<ProtectionActionType> MANAGEMENT_COPY =
            EnumSet.copyOf(MANAGEMENT_ACTIONS);

    private static final Map<String, ProtectionActionType> BY_SUBCOMMAND = Map.ofEntries(
            Map.entry("trust", ProtectionActionType.MANAGE_MEMBER),
            Map.entry("untrust", ProtectionActionType.MANAGE_MEMBER),
            Map.entry("default", ProtectionActionType.MANAGE_PERMISSION),
            Map.entry("binding", ProtectionActionType.MANAGE_PERMISSION),
            Map.entry("ban", ProtectionActionType.MANAGE_MEMBER),
            Map.entry("unban", ProtectionActionType.MANAGE_MEMBER),
            Map.entry("subland", ProtectionActionType.MANAGE_SUBLAND),
            Map.entry("expand", ProtectionActionType.EXPAND_LAND),
            Map.entry("shrink", ProtectionActionType.EXPAND_LAND),
            Map.entry("unclaim", ProtectionActionType.EXPAND_LAND),
            Map.entry("explain", ProtectionActionType.MANAGE_PERMISSION),
            Map.entry("inspect", ProtectionActionType.MANAGE_PERMISSION),
            Map.entry("delete", ProtectionActionType.DELETE_LAND));

    private ManagementPermissionGate() {
    }

    /** Whether this gate enforces the given action. */
    public static boolean isManagementAction(ProtectionActionType action) {
        return action != null && MANAGEMENT_COPY.contains(action);
    }

    /**
     * Maps a {@code /land} subcommand to its management action. Subcommands
     * without a management action (help, wand, claim, confirm, rename) yield
     * {@link Optional#empty()}; their authorisation is owned elsewhere.
     */
    public static Optional<ProtectionActionType> actionForSubcommand(String subcommand) {
        if (subcommand == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_SUBCOMMAND.get(subcommand.toLowerCase(Locale.ROOT)));
    }

    /** Whether the given snapshot is a Server Land. */
    public static boolean isServerLand(LandSnapshot land) {
        Objects.requireNonNull(land, "land");
        return land.ownerRef() instanceof OwnerRef.ServerOwnerRef;
    }

    /**
     * Resolves the management decision for one operation.
     *
     * @param actor actor attempting the operation
     * @param landId target land
     * @param action one of {@link #MANAGEMENT_ACTIONS}
     * @param snapshot already-published immutable registry snapshot
     * @param adminBypass admin-bypass state resolved outside the gate
     * @param serverLandSteward whether the actor holds the server-land steward
     *                          grant ({@code chunkland.admin.serverland} node),
     *                          resolved outside the gate
     * @param provider snapshot-backed context provider
     * @return ALLOW when the operation may proceed, DENY otherwise (fail-closed)
     * @throws IllegalArgumentException when the action is not a management action
     */
    public static PermissionDecision check(UUID actor,
                                           LandId landId,
                                           ProtectionActionType action,
                                           LandRegistry snapshot,
                                           boolean adminBypass,
                                           boolean serverLandSteward,
                                           PermissionContextProvider provider) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(provider, "provider");
        if (!isManagementAction(action)) {
            throw new IllegalArgumentException(
                    "not a management action (this gate only enforces " + MANAGEMENT_ACTIONS + "): " + action);
        }
        LandSnapshot land = snapshot.land(landId);
        if (land == null) {
            return new PermissionDecision(PermissionState.DENY, DecisionSource.SUBJECT_PERMISSION,
                    "Management gate: unknown land " + landId + " for " + action + " -> DENY (fail-closed)");
        }
        if (adminBypass) {
            return new PermissionDecision(PermissionState.ALLOW, DecisionSource.SUBJECT_PERMISSION,
                    "Admin bypass: full protection bypass -> ALLOW for " + action);
        }
        if (isServerLand(land) && !serverLandSteward) {
            return new PermissionDecision(PermissionState.DENY, DecisionSource.SUBJECT_PERMISSION,
                    "Management gate: Server Land " + landId + " requires the server-land steward"
                            + " grant for " + action + " -> DENY (fail-closed, namespaces disjoint)");
        }
        if (!adminBypass && serverLandSteward && isServerLand(land)) {
            return new PermissionDecision(PermissionState.ALLOW, DecisionSource.SUBJECT_PERMISSION,
                    "Server-land steward: actor holds the server-land grant on Server Land "
                            + landId + " for " + action + " -> ALLOW (owner-equivalent, no admin bypass)");
        }
        return PermissionResolver.resolve(provider.provide(actor, landId, action, snapshot));
    }

    /**
     * Convenience predicate over {@link #check}: {@code true} only on ALLOW.
     * A denied or failed resolution is {@code false}; use {@link #check} when
     * the explanation is needed for the rejection message.
     */
    public static boolean isAllowed(UUID actor,
                                   LandId landId,
                                   ProtectionActionType action,
                                   LandRegistry snapshot,
                                   boolean adminBypass,
                                   boolean serverLandSteward,
                                   PermissionContextProvider provider) {
        return check(actor, landId, action, snapshot, adminBypass, serverLandSteward, provider).outcome()
                == PermissionState.ALLOW;
    }
}
