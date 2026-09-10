package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable view of the durable land authorisation layers: direct player
 * grants per land plus the land default per action.
 *
 * <p>The generic layers carry reusable-profile bindings: per-land and
 * per-subland {@link PermissionBinding} lists in durable-expanded form plus
 * the group membership map that decides which group bindings one actor may
 * see. Sparse subland defaults sit beside them; an unset subland default
 * reads as {@code INHERIT} so decisions fall through to the land layers.
 *
 * <p>Instances are published through a volatile holder, so readers observe
 * one complete version and previously captured references keep answering
 * with their own values. Accessors only read immutable maps and never touch
 * storage, the server, or the network. Missing data answers empty or
 * {@code INHERIT} so decisions fall through fail-closed.
 *
 * <p>Every snapshot carries its load validity: {@link #empty()} and
 * {@link #copyOf} are successful loads (an empty load legitimately means
 * nobody is bound, defaulted, or banned), while {@link #unloaded()} marks a
 * value that must never authorise — cache startup before the first durable
 * load and every failed reload publish it so readers fail closed instead
 * of trusting an empty or stale view.
 */
public final class LandAuthorisationSnapshot {

    private final Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct;
    private final Map<LandId, Map<ProtectionActionType, PermissionState>> defaults;
    private final Map<LandId, Set<UUID>> bans;
    private final Map<LandId, List<PermissionBinding>> genericLand;
    private final Map<SubLandId, List<PermissionBinding>> genericSubland;
    private final Map<String, Set<UUID>> groupMembers;
    private final Map<SubLandId, Map<ProtectionActionType, PermissionState>> sublandDefaults;
    private final boolean loaded;

    private LandAuthorisationSnapshot(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults,
            Map<LandId, Set<UUID>> bans,
            Map<LandId, List<PermissionBinding>> genericLand,
            Map<SubLandId, List<PermissionBinding>> genericSubland,
            Map<String, Set<UUID>> groupMembers,
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> sublandDefaults,
            boolean loaded) {
        this.direct = direct;
        this.defaults = defaults;
        this.bans = bans;
        this.genericLand = genericLand;
        this.genericSubland = genericSubland;
        this.groupMembers = groupMembers;
        this.sublandDefaults = sublandDefaults;
        this.loaded = loaded;
    }

    /** Empty snapshot from a successful load: no bindings, every land default {@code INHERIT}, nobody banned. */
    public static LandAuthorisationSnapshot empty() {
        return new LandAuthorisationSnapshot(Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    /**
     * Unloaded marker for cache startup and failed reloads: carries no data
     * and must never authorise. Readers check {@link #loaded()} first and
     * fail closed on {@code false} instead of treating the empty maps as a
     * known-unbanned answer.
     */
    public static LandAuthorisationSnapshot unloaded() {
        return new LandAuthorisationSnapshot(Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), false);
    }

    /**
     * Whether this snapshot came from a successful durable load and may
     * authorise. {@code false} means startup-before-load or a failed reload:
     * ban lookups must answer empty and subject lookups must deny rather
     * than fall through to world/global defaults.
     */
    public boolean loaded() {
        return loaded;
    }

    /**
     * Defensive copy of both layers; {@code null} layers read as empty.
     * Every nested map and set is copied into an unmodifiable view, so later
     * changes to the inputs can never leak into the snapshot.
     */
    public static LandAuthorisationSnapshot copyOf(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults) {
        return new LandAuthorisationSnapshot(copyDirect(direct), copyDefaults(defaults), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    /**
     * Defensive copy of all three layers, including the per-land ENTRY ban
     * set; {@code null} layers read as empty. Every nested map and set is
     * copied into an unmodifiable view, so later changes to the inputs can
     * never leak into the snapshot.
     */
    public static LandAuthorisationSnapshot copyOf(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults,
            Map<LandId, Set<UUID>> bans) {
        return new LandAuthorisationSnapshot(copyDirect(direct), copyDefaults(defaults),
                copyBans(bans), Map.of(), Map.of(), Map.of(), Map.of(), true);
    }

    /**
     * Defensive copy of every layer, including the generic profile bindings
     * per land and per subland, the group membership map and the sparse
     * subland defaults; {@code null} layers read as empty. Every nested
     * collection is copied into an unmodifiable view, so later changes to
     * the inputs can never leak into the snapshot.
     */
    public static LandAuthorisationSnapshot copyOf(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults,
            Map<LandId, Set<UUID>> bans,
            Map<LandId, List<PermissionBinding>> genericLand,
            Map<SubLandId, List<PermissionBinding>> genericSubland,
            Map<String, Set<UUID>> groupMembers,
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> sublandDefaults) {
        return new LandAuthorisationSnapshot(copyDirect(direct), copyDefaults(defaults),
                copyBans(bans), copyBindingLists(genericLand), copySubBindingLists(genericSubland),
                copyMembers(groupMembers), copySubDefaults(sublandDefaults), true);
    }

    /**
     * Rebuild with refreshed direct layers while keeping the generic layers
     * of this snapshot. Lets the direct-trust refresh path republish without
     * wiping bindings it does not own. The result always reads as loaded: it
     * carries a fresh successful direct load.
     */
    public LandAuthorisationSnapshot withDirect(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> direct,
            Map<LandId, Map<ProtectionActionType, PermissionState>> defaults,
            Map<LandId, Set<UUID>> bans) {
        return new LandAuthorisationSnapshot(copyDirect(direct), copyDefaults(defaults),
                copyBans(bans), genericLand, genericSubland, groupMembers, sublandDefaults,
                true);
    }

    /**
     * Rebuild with refreshed generic layers while keeping the direct layers
     * of this snapshot. Lets the binding refresh path republish without
     * touching the direct-trust data it does not own. A failed generic load
     * must publish {@link #unloaded()} instead — never merge into a stale
     * view.
     */
    public LandAuthorisationSnapshot withGeneric(
            Map<LandId, List<PermissionBinding>> genericLand,
            Map<SubLandId, List<PermissionBinding>> genericSubland,
            Map<String, Set<UUID>> groupMembers,
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> sublandDefaults) {
        return new LandAuthorisationSnapshot(direct, defaults, bans,
                copyBindingLists(genericLand), copySubBindingLists(genericSubland),
                copyMembers(groupMembers), copySubDefaults(sublandDefaults), loaded);
    }

    /**
     * Whitelisted actions the actor is directly granted on the land. Never
     * null and unmodifiable; empty when the actor holds no binding there.
     */
    public Set<ProtectionActionType> directAllows(UUID actor, LandId landId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Map<UUID, Set<ProtectionActionType>> byPlayer = direct.get(landId);
        if (byPlayer == null) {
            return Set.of();
        }
        Set<ProtectionActionType> allows = byPlayer.get(actor);
        return allows == null ? Set.of() : allows;
    }

    /**
     * Whether the actor is ENTRY-banned on the land. Never throws for
     * unknown lands or actors: missing data answers {@code false} so the
     * decision falls through to the normal trust/default chain. Callers
     * must check {@link #loaded()} first: an unloaded snapshot also answers
     * {@code false} here, which is why lookups fail closed on it instead of
     * trusting this value.
     */
    public boolean isBanned(UUID actor, LandId landId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Set<UUID> banned = bans.get(landId);
        return banned != null && banned.contains(actor);
    }

    /**
     * Durable land default for one action, or {@code INHERIT} when unset.
     * Never null.
     */
    public PermissionState landDefault(LandId landId, ProtectionActionType action) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Map<ProtectionActionType, PermissionState> byAction = defaults.get(landId);
        if (byAction == null) {
            return PermissionState.INHERIT;
        }
        return byAction.getOrDefault(action, PermissionState.INHERIT);
    }

    /**
     * Generic profile bindings on one land in durable-expanded form. The
     * lookup keeps a player binding only for its own subject and a group
     * binding only for members (see {@link #isGroupMember}); anyone else
     * sees none of them. Never null and unmodifiable.
     */
    public List<PermissionBinding> genericLandBindings(LandId landId) {
        Objects.requireNonNull(landId, "landId");
        List<PermissionBinding> bindings = genericLand.get(landId);
        return bindings == null ? List.of() : bindings;
    }

    /**
     * Generic profile bindings on one subland in durable-expanded form,
     * filtered by the lookup exactly like the land layer. Never null and
     * unmodifiable.
     */
    public List<PermissionBinding> genericSublandBindings(SubLandId sublandId) {
        Objects.requireNonNull(sublandId, "sublandId");
        List<PermissionBinding> bindings = genericSubland.get(sublandId);
        return bindings == null ? List.of() : bindings;
    }

    /**
     * Whether the actor belongs to the group named by one group binding's
     * subject id. Missing groups answer {@code false} so non-members never
     * observe a group binding.
     *
     * @param groupSubjectId the group binding's subject id (a group UUID
     *                       rendered as text)
     */
    public boolean isGroupMember(String groupSubjectId, UUID actor) {
        Objects.requireNonNull(groupSubjectId, "groupSubjectId");
        Objects.requireNonNull(actor, "actor");
        Set<UUID> members = groupMembers.get(groupSubjectId);
        return members != null && members.contains(actor);
    }

    /**
     * Durable subland default for one action, or {@code INHERIT} when unset.
     * Never null.
     */
    public PermissionState sublandDefault(SubLandId sublandId, ProtectionActionType action) {
        Objects.requireNonNull(sublandId, "sublandId");
        Objects.requireNonNull(action, "action");
        Map<ProtectionActionType, PermissionState> byAction = sublandDefaults.get(sublandId);
        if (byAction == null) {
            return PermissionState.INHERIT;
        }
        return byAction.getOrDefault(action, PermissionState.INHERIT);
    }

    private static Map<LandId, List<PermissionBinding>> copyBindingLists(
            Map<LandId, List<PermissionBinding>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, List<PermissionBinding>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, List<PermissionBinding>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            List<PermissionBinding> bindings = new ArrayList<>(Objects.requireNonNull(
                    entry.getValue(), "generic bindings for " + landId));
            for (PermissionBinding binding : bindings) {
                Objects.requireNonNull(binding, "generic binding for " + landId);
            }
            copy.put(landId, Collections.unmodifiableList(bindings));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<SubLandId, List<PermissionBinding>> copySubBindingLists(
            Map<SubLandId, List<PermissionBinding>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<SubLandId, List<PermissionBinding>> copy = new HashMap<>(source.size());
        for (Map.Entry<SubLandId, List<PermissionBinding>> entry : source.entrySet()) {
            SubLandId sublandId = Objects.requireNonNull(entry.getKey(), "subland key");
            List<PermissionBinding> bindings = new ArrayList<>(Objects.requireNonNull(
                    entry.getValue(), "generic bindings for " + sublandId));
            for (PermissionBinding binding : bindings) {
                Objects.requireNonNull(binding, "generic binding for " + sublandId);
            }
            copy.put(sublandId, Collections.unmodifiableList(bindings));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Set<UUID>> copyMembers(Map<String, Set<UUID>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<UUID>> copy = new HashMap<>(source.size());
        for (Map.Entry<String, Set<UUID>> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "group key");
            Set<UUID> members = new HashSet<>(Objects.requireNonNull(
                    entry.getValue(), "group members for " + key));
            for (UUID member : members) {
                Objects.requireNonNull(member, "group member for " + key);
            }
            copy.put(key, Collections.unmodifiableSet(members));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<SubLandId, Map<ProtectionActionType, PermissionState>> copySubDefaults(
            Map<SubLandId, Map<ProtectionActionType, PermissionState>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<SubLandId, Map<ProtectionActionType, PermissionState>> copy =
                new HashMap<>(source.size());
        for (Map.Entry<SubLandId, Map<ProtectionActionType, PermissionState>> entry
                : source.entrySet()) {
            SubLandId sublandId = Objects.requireNonNull(entry.getKey(), "subland key");
            Map<ProtectionActionType, PermissionState> byAction = Objects.requireNonNull(
                    entry.getValue(), "subland defaults for " + sublandId);
            EnumMap<ProtectionActionType, PermissionState> states =
                    new EnumMap<>(ProtectionActionType.class);
            for (Map.Entry<ProtectionActionType, PermissionState> state : byAction.entrySet()) {
                states.put(Objects.requireNonNull(state.getKey(), "action key"),
                        Objects.requireNonNull(state.getValue(), "default state"));
            }
            copy.put(sublandId, Collections.unmodifiableMap(states));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<LandId, Set<UUID>> copyBans(Map<LandId, Set<UUID>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, Set<UUID>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, Set<UUID>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            Set<UUID> banned = Objects.requireNonNull(
                    entry.getValue(), "entry bans for " + landId);
            Set<UUID> players = new HashSet<>(banned.size());
            for (UUID player : banned) {
                players.add(Objects.requireNonNull(player, "banned player"));
            }
            copy.put(landId, Collections.unmodifiableSet(players));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<LandId, Map<UUID, Set<ProtectionActionType>>> copyDirect(
            Map<LandId, Map<UUID, Set<ProtectionActionType>>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, Map<UUID, Set<ProtectionActionType>>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, Map<UUID, Set<ProtectionActionType>>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            Map<UUID, Set<ProtectionActionType>> byPlayer = Objects.requireNonNull(
                    entry.getValue(), "direct grants for " + landId);
            Map<UUID, Set<ProtectionActionType>> players = new HashMap<>(byPlayer.size());
            for (Map.Entry<UUID, Set<ProtectionActionType>> player : byPlayer.entrySet()) {
                UUID actor = Objects.requireNonNull(player.getKey(), "actor key");
                Set<ProtectionActionType> allows = Objects.requireNonNull(
                        player.getValue(), "direct grants for " + actor);
                players.put(actor, Set.copyOf(allows));
            }
            copy.put(landId, Collections.unmodifiableMap(players));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<LandId, Map<ProtectionActionType, PermissionState>> copyDefaults(
            Map<LandId, Map<ProtectionActionType, PermissionState>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<LandId, Map<ProtectionActionType, PermissionState>> copy = new HashMap<>(source.size());
        for (Map.Entry<LandId, Map<ProtectionActionType, PermissionState>> entry : source.entrySet()) {
            LandId landId = Objects.requireNonNull(entry.getKey(), "land key");
            Map<ProtectionActionType, PermissionState> byAction = Objects.requireNonNull(
                    entry.getValue(), "land defaults for " + landId);
            EnumMap<ProtectionActionType, PermissionState> states =
                    new EnumMap<>(ProtectionActionType.class);
            for (Map.Entry<ProtectionActionType, PermissionState> state : byAction.entrySet()) {
                states.put(Objects.requireNonNull(state.getKey(), "action key"),
                        Objects.requireNonNull(state.getValue(), "default state"));
            }
            copy.put(landId, Collections.unmodifiableMap(states));
        }
        return Collections.unmodifiableMap(copy);
    }
}
