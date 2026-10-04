package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.selection.SelectionClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * ENTRY transit semantics: every way in is checked, denies push out, and
 * players banned while already inside are stopped on their next move.
 *
 * <p>Teleport coverage: the destination ENTRY decision applies to every
 * {@link PlayerTeleportEvent.TeleportCause} (ender pearl, chorus fruit,
 * plugin-driven, nether/end portals, and any future cause): nothing bypasses
 * by choosing a different vector. The predicate
 * {@link #requiresDestinationCheck} pins that rule for tests; dispatch itself
 * stays in the listener, which always decides at the destination.
 *
 * <p>Push-out: an entry deny ejects under a per-player throttle
 * ({@link #DEFAULT_PUSH_OUT_COOLDOWN} unless constructed otherwise) so a
 * stuck client hammering the border costs one teleport per window. Transport
 * runs over {@code teleportAsync}: sync {@code Entity#teleport} is broken on
 * Folia and must never be used, and the transport must defer to a later tick
 * because an inline teleport from the deny handler is undone by the platform
 * before the client ever sees it (see {@link PushOutSink}). The target
 * prefers the pre-entry position
 * (the boundary side) and falls back to the world spawn; each candidate is
 * used only when its chunk is already loaded (verified through
 * {@link ChunkLoadedCheck}), the player is not ENTRY-denied there (verified
 * through {@link EntryAllowedCheck}), <em>and</em> the player is not banned
 * there (so the landing never walks straight into another banned-inside
 * stop). When no candidate passes all checks the deny stands as cancel-only:
 * this adapter never loads a chunk and never sends a player back into a
 * denying land. A missing entry check verifies nothing and therefore allows
 * nothing (fail-closed, cancel-only). The throttle slot is spent before
 * target resolution, so the exhausted-candidate path records one warning
 * log per window no matter how fast a stuck player hammers the border;
 * routine ejects stay quiet. A transport that refuses the landing is recorded
 * the same way instead of reading as a successful eject.
 *
 * <p>Retreat: a walked-in deny does not put the player back on the border
 * itself. When a {@link LandingCheck} is wired, the landing is moved
 * {@link #RETREAT_DISTANCE} blocks away from the side that was crossed, so
 * a player who keeps walking gets a moment of free movement instead of
 * being stopped again on the very next tick. Shorter distances are tried
 * when the full retreat has no standing room or is itself denied, and the
 * plain pre-entry position stays the last resort.
 *
 * <p>Escape: a player who is banned while standing inside is not sent to
 * the world spawn when a way out is close. With a {@link LandingCheck}
 * wired, the nearest side of the banning land is found by walking the four
 * axis directions chunk by chunk (at most {@link #ESCAPE_SEARCH_CHUNKS}),
 * and the player lands {@link #RETREAT_DISTANCE} blocks outside it. The
 * world spawn stays the fallback when no side is close, loaded, and safe.
 *
 * <p>A push-out teleport always departs from the denying area, so without
 * help the banned-inside stop would cancel the very teleport sent to rescue
 * the player and wedge them inside. Every initiated push-out therefore
 * registers a single-use pass for its validated landing
 * ({@link #consumePushOutPass}): the next teleport event arriving at exactly
 * that block skips the origin ban stop once, while the destination ENTRY
 * check still runs. The pass is claimed for every arrival before the origin
 * ban stop runs, so ENTRY-deny rescues (whose landing is not banned) spend
 * it too instead of leaving it behind; quit discards any pass still
 * pending. Passes never widen anything else, and an unmatched or
 * replayed arrival stays fully enforced.
 *
 * <p>Banned-inside: ban writes and storage live in the durable ENTRY ban
 * table; this adapter only defines the read seam ({@link BanLookup}) and
 * the enforcement meaning. A {@code null} lookup means no ban source is
 * wired yet, and the inside check skips (the destination ENTRY decision
 * still applies). A wired lookup that answers empty, {@code null}, or
 * throws is treated as banned (fail-closed): a missing answer must not
 * grant movement.
 *
 * <p>Production wiring ({@link EntryBanLookup} through
 * {@link ProtectionListener}) always supplies a real snapshot-backed query,
 * so banned-inside enforcement is live; the {@code null} path stays only
 * for unit tests of this adapter itself.
 *
 * <p>Hot-path contract for every seam: memory-only reads, no blocking, no
 * cross-region calls, no chunk loads, no storage access. All state lives in a
 * {@link ConcurrentHashMap}; the event thread never waits.
 */
public final class EntryProtectionAdapter {

    /** Default quiet window between two push-outs for one player. */
    public static final Duration DEFAULT_PUSH_OUT_COOLDOWN = Duration.ofSeconds(3);

    /**
     * Quiet window for the retreating push-out. A retreat buys the player
     * roughly this long before they can reach the border again, so every
     * contact ends in one clean step back instead of a run of cancelled
     * moves.
     */
    public static final Duration RETREAT_PUSH_OUT_COOLDOWN = Duration.ofMillis(500);

    /** Blocks between the crossed border and the retreat landing. */
    public static final int RETREAT_DISTANCE = 3;

    /** Chunks walked in each direction when looking for a way out of a ban. */
    public static final int ESCAPE_SEARCH_CHUNKS = 8;

    /** Shortest window between two warning records for one player. */
    private static final Duration WARNING_WINDOW = DEFAULT_PUSH_OUT_COOLDOWN;

    /**
     * Read seam for the banned-inside check. Implemented by ban storage later;
     * this adapter never writes bans.
     *
     * @return {@code true} when the player is banned at the given chunk,
     *         {@code false} when they may stay; empty means unknown and is
     *         treated as banned (fail-closed)
     */
    @FunctionalInterface
    public interface BanLookup {
        Optional<Boolean> bannedAt(UUID playerId, UUID worldId, int chunkX, int chunkZ);
    }

    /**
     * Loaded-state probe for push-out targets. Production passes
     * {@link World#isChunkLoaded(int, int)}, which reports without loading.
     */
    @FunctionalInterface
    public interface ChunkLoadedCheck {
        boolean isLoaded(World world, int chunkX, int chunkZ);

        /** Guarded Bukkit probe: any failure reports unloaded (cancel-only). */
        static ChunkLoadedCheck bukkit() {
            return (world, chunkX, chunkZ) -> {
                try {
                    return world != null && world.isChunkLoaded(chunkX, chunkZ);
                } catch (RuntimeException ex) {
                    return false;
                }
            };
        }
    }

    /**
     * Push-out transport. Production hands the landing to the player's Folia
     * thread for a later tick and travels over {@code teleportAsync} there
     * (sync {@code Entity#teleport} is broken on Folia).
     *
     * <p>The contract exists because an inline teleport from a deny handler is
     * silently undone: Paper fires {@code PlayerMoveEvent} inside
     * {@code ServerGamePacketListenerImpl#handleMovePlayer} and, once the event
     * comes back cancelled, immediately restores the pre-move position with
     * {@code internalTeleport(from)}. On the player's own region thread
     * {@code teleportAsync} resolves inline, so an ejection applied from the
     * handler is overwritten in the same call — the player never moves and
     * nothing throws.
     */
    @FunctionalInterface
    public interface PushOutSink {
        /**
         * @return {@code true} when the transport took the landing for delivery,
         *         {@code false} when the platform refused it (retired thread,
         *         thrown call). Failures the platform only reports later, on the
         *         teleport's completion, are the transport's own to record.
         */
        boolean teleport(Player player, Location target);
    }

    /**
     * Standing-room probe for a retreat landing. Production reads the blocks
     * around the candidate only when its chunk is loaded and owned by the
     * calling thread, and never loads anything.
     */
    @FunctionalInterface
    public interface LandingCheck {
        /**
         * @return the candidate, adjusted to a height the player can stand
         *         at, or {@code null} when the spot is not safe or cannot be
         *         verified
         */
        Location settle(Player player, Location candidate);

        /**
         * Same answer for a landing that may sit far from the player, where
         * the ground can be at a very different height. Defaults to
         * {@link #settle}; production searches a taller column.
         */
        default Location settleFar(Player player, Location candidate) {
            return settle(player, candidate);
        }
    }

    /**
     * Live comfort tuning for the push-out. Production reads the current
     * config snapshot on every call, so a reload applies to the next deny
     * without rebuilding the adapter. Memory reads only.
     */
    public interface Tuning {
        /** Quiet window between two push-outs for one player. */
        Duration pushOutCooldown();

        /** Blocks between the crossed border and the landing. */
        int retreatDistance();
    }

    /**
     * ENTRY-validity probe for push-out targets. Production answers from the
     * engine destination decision ({@code ENTRY DENY} means not allowed);
     * anything unverifiable answers {@code false} (fail-closed, cancel-only).
     */
    @FunctionalInterface
    public interface EntryAllowedCheck {
        boolean allowed(UUID playerId, Location at);
    }

    private final SelectionClock clock;
    private final Duration pushOutCooldown;
    private final BanLookup bans;
    private final ChunkLoadedCheck chunks;
    private final EntryAllowedCheck entryCheck;
    private final LandingCheck landing;
    private final Tuning tuning;
    private final PushOutSink sink;
    private final ConcurrentMap<UUID, Instant> lastPushOut = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Instant> lastWarned = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Location> pushOutPasses = new ConcurrentHashMap<>();

    private static final Logger LOG = Logger.getLogger(EntryProtectionAdapter.class.getName());

    /**
     * @param clock            time source; tests advance a fake clock instead
     *                         of sleeping the event thread
     * @param pushOutCooldown  quiet window per player ({@code null} selects
     *                         {@link #DEFAULT_PUSH_OUT_COOLDOWN})
     * @param bans             ban read seam; {@code null} means no ban source
     *                         is wired yet and the inside check skips
     * @param chunks           loaded-state probe, never a loader
     * @param sink             push-out transport
     */
    public EntryProtectionAdapter(SelectionClock clock, Duration pushOutCooldown,
                                  BanLookup bans, ChunkLoadedCheck chunks, PushOutSink sink) {
        this(clock, pushOutCooldown, bans, chunks, null, sink);
    }

    /**
     * @param clock            time source; tests advance a fake clock instead
     *                         of sleeping the event thread
     * @param pushOutCooldown  quiet window per player ({@code null} selects
     *                         {@link #DEFAULT_PUSH_OUT_COOLDOWN})
     * @param bans             ban read seam; {@code null} means no ban source
     *                         is wired yet and the inside check skips
     * @param chunks           loaded-state probe, never a loader
     * @param entryCheck       ENTRY-validity probe per push-out candidate;
     *                         {@code null} allows nothing (fail-closed)
     * @param sink             push-out transport
     */
    public EntryProtectionAdapter(SelectionClock clock, Duration pushOutCooldown,
                                  BanLookup bans, ChunkLoadedCheck chunks,
                                  EntryAllowedCheck entryCheck, PushOutSink sink) {
        this(clock, pushOutCooldown, bans, chunks, entryCheck, null, sink);
    }

    /**
     * @param clock            time source; tests advance a fake clock instead
     *                         of sleeping the event thread
     * @param pushOutCooldown  quiet window per player ({@code null} selects
     *                         {@link #DEFAULT_PUSH_OUT_COOLDOWN})
     * @param bans             ban read seam; {@code null} means no ban source
     *                         is wired yet and the inside check skips
     * @param chunks           loaded-state probe, never a loader
     * @param entryCheck       ENTRY-validity probe per push-out candidate;
     *                         {@code null} allows nothing (fail-closed)
     * @param landing          standing-room probe for the retreat landing;
     *                         {@code null} disables the retreat, so a deny
     *                         lands on the pre-entry position
     * @param sink             push-out transport
     */
    public EntryProtectionAdapter(SelectionClock clock, Duration pushOutCooldown,
                                  BanLookup bans, ChunkLoadedCheck chunks,
                                  EntryAllowedCheck entryCheck, LandingCheck landing,
                                  PushOutSink sink) {
        this(clock, pushOutCooldown, bans, chunks, entryCheck, landing, null, sink);
    }

    /**
     * @param tuning live cooldown and retreat distance; {@code null}, a
     *               failing read or an out-of-range value keeps the fixed
     *               {@code pushOutCooldown} and {@link #RETREAT_DISTANCE}.
     *               Every other parameter matches the overload above.
     */
    public EntryProtectionAdapter(SelectionClock clock, Duration pushOutCooldown,
                                  BanLookup bans, ChunkLoadedCheck chunks,
                                  EntryAllowedCheck entryCheck, LandingCheck landing,
                                  Tuning tuning, PushOutSink sink) {
        this.tuning = tuning;
        this.clock = Objects.requireNonNull(clock, "clock");
        Duration interval = pushOutCooldown == null ? DEFAULT_PUSH_OUT_COOLDOWN : pushOutCooldown;
        if (interval.isNegative()) {
            throw new IllegalArgumentException("pushOutCooldown must not be negative: " + interval);
        }
        this.pushOutCooldown = interval;
        this.bans = bans;
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.entryCheck = entryCheck;
        this.landing = landing;
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Every teleport vector checks destination ENTRY. The argument exists so
     * tests pin each cause by name; the answer is unconditionally
     * {@code true}, including for {@code null} or future causes.
     */
    public static boolean requiresDestinationCheck(
            PlayerTeleportEvent.TeleportCause cause) {
        return true;
    }

    /**
     * Whether the player is banned at their current position and must be
     * stopped even when the destination ENTRY decision would allow.
     *
     * @return {@code true} when movement must be denied: the lookup says
     *         banned, or the lookup is wired but its answer is missing or
     *         fails. {@code false} only when no ban source is wired yet, or
     *         the wired source explicitly clears the player.
     */
    public boolean isBannedInside(UUID playerId, Location at) {
        BanLookup lookup = this.bans;
        if (lookup == null) {
            return false;
        }
        try {
            if (playerId == null || at == null || at.getWorld() == null) {
                return true;
            }
            Optional<Boolean> answer = lookup.bannedAt(playerId, at.getWorld().getUID(),
                    at.getBlockX() >> 4, at.getBlockZ() >> 4);
            if (answer == null || answer.isEmpty() || answer.get() == null) {
                return true;
            }
            return answer.get();
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /**
     * Tries to spend the push-out slot for this player. The claim is atomic:
     * racing denies for one player resolve to a single winner through a
     * compare-and-set loop, so concurrent border hammering still costs one
     * teleport per window.
     *
     * @return {@code true} when a push-out may proceed now (first deny, or the
     *         window has expired); the send time is recorded before the
     *         transport runs. {@code false} when the player is still inside
     *         their window.
     */
    public boolean tryAcquirePushOut(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = Objects.requireNonNull(clock.now(), "clock must not return null");
        Duration window = currentCooldown();
        while (true) {
            Instant previous = lastPushOut.get(playerId);
            if (previous != null && Duration.between(previous, now).compareTo(window) < 0) {
                return false;
            }
            if (previous == null) {
                if (lastPushOut.putIfAbsent(playerId, now) == null) {
                    return true;
                }
            } else if (lastPushOut.replace(playerId, previous, now)) {
                return true;
            }
        }
    }

    /**
     * Picks the push-out target without loading anything: the pre-entry
     * position when its chunk is loaded, ENTRY allows the player there, and
     * the player is not banned there, else the world spawn under the same
     * three checks, else empty (deny stands as cancel-only). A denied,
     * banning, or unverifiable candidate is skipped, never teleported into.
     */
    public Optional<Location> pushOutTarget(Player player, Location from) {
        if (player == null) {
            return Optional.empty();
        }
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (playerId == null) {
            return Optional.empty();
        }
        try {
            if (from != null && from.getWorld() != null && usableTarget(playerId, from)) {
                return Optional.of(from);
            }
            World world = player.getWorld();
            if (world == null && from != null) {
                world = from.getWorld();
            }
            if (world == null) {
                return Optional.empty();
            }
            Location spawn;
            try {
                spawn = world.getSpawnLocation();
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
            if (!usableTarget(playerId, spawn)) {
                return Optional.empty();
            }
            return Optional.of(spawn);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Full deny path: spend the throttle slot first, then resolve a usable
     * target and hand it to the transport. The throttle runs before
     * target resolution on purpose: a wedged player hammering the border
     * costs one teleport <em>and</em> one observable record per window,
     * never one log line per move event. Every failure (throttled, no
     * usable target, refused or throwing transport) yields {@code false},
     * leaves the cancel in place, and drops the pass that landing can no
     * longer claim; nothing here throws for enforcement-path failures.
     * A no-candidate occurrence also spends the window, so a still-stuck
     * player is recorded once per window until a usable target appears.
     *
     * @return {@code true} when the transport took the landing for delivery,
     *         {@code false} when the player stays put
     */
    public boolean pushOut(Player player, Location from) {
        return pushOut(player, from, null);
    }

    /**
     * Deny path for a walked-in crossing: same contract as
     * {@link #pushOut(Player, Location)}, but the landing retreats
     * {@link #RETREAT_DISTANCE} blocks from the border between {@code from}
     * and {@code deniedTo} when a {@link LandingCheck} is wired and a safe,
     * ENTRY-allowed, ban-free spot exists there. Without one the landing
     * falls back to the pre-entry position and then the world spawn.
     *
     * @param deniedTo the position the player was refused at; {@code null}
     *                 skips the retreat
     */
    public boolean pushOut(Player player, Location from, Location deniedTo) {
        if (player == null) {
            return false;
        }
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ex) {
            return false;
        }
        if (playerId == null) {
            return false;
        }
        try {
            if (!tryAcquirePushOut(playerId)) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        Optional<Location> target = retreatTarget(player, playerId, from, deniedTo);
        if (target.isEmpty()) {
            target = pushOutTarget(player, from);
        }
        return deliver(player, playerId, target);
    }

    /**
     * Hands a resolved landing to the transport, registering its rescue pass
     * first. An empty landing or a refusing transport leaves the cancel in
     * place and is recorded once per warning window.
     */
    private boolean deliver(Player player, UUID playerId, Optional<Location> target) {
        if (target.isEmpty()) {
            if (mayWarn(playerId)) {
                LOG.warning("ChunkLand push-out found no push-out target for a denied move: "
                        + "no candidate chunk is loaded, ENTRY-allowed, and ban-free, "
                        + "so the deny stands as cancel-only");
            }
            return false;
        }
        Location landing = target.get();
        pushOutPasses.put(playerId, landing);
        boolean accepted;
        try {
            accepted = sink.teleport(player, landing);
        } catch (RuntimeException ex) {
            accepted = false;
        }
        if (!accepted) {
            // The landing never left this thread, so no arrival can claim the
            // pass; leaving it would waive the origin ban stop for an unrelated
            // teleport later.
            discardPass(playerId, landing);
            if (mayWarn(playerId)) {
                LOG.warning("ChunkLand push-out transport refused the landing for a denied "
                        + "move: the platform rejected the ejection, so the deny stands as "
                        + "cancel-only");
            }
        }
        return accepted;
    }

    /**
     * Deny path for a player banned where they stand: same contract as
     * {@link #pushOut(Player, Location)}, but the landing is the nearest spot
     * just outside the banning land when one can be verified, and the world
     * spawn only otherwise.
     *
     * @param inside the banned position the player currently occupies
     */
    public boolean pushOutOfBan(Player player, Location inside) {
        if (player == null) {
            return false;
        }
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ex) {
            return false;
        }
        if (playerId == null) {
            return false;
        }
        try {
            if (!tryAcquirePushOut(playerId)) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        Optional<Location> target = escapeTarget(player, playerId, inside);
        if (target.isEmpty()) {
            target = pushOutTarget(player, null);
        }
        return deliver(player, playerId, target);
    }

    /** One way out of a banning land: the axis direction and how far it is. */
    private record Exit(int stepX, int stepZ, double border, double distance) {
    }

    /**
     * Nearest verified landing just outside the land that bans the player.
     * Each axis direction is walked chunk by chunk until the ban ends; the
     * closest ends are tried first, each at the full retreat distance and
     * then shorter. Empty when no probe is wired, nothing ends within reach,
     * or no landing passes the standing-room, loaded, ENTRY and ban checks.
     */
    private Optional<Location> escapeTarget(Player player, UUID playerId, Location inside) {
        LandingCheck check = this.landing;
        if (check == null || inside == null) {
            return Optional.empty();
        }
        try {
            World world = inside.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            int chunkX = inside.getBlockX() >> 4;
            int chunkZ = inside.getBlockZ() >> 4;
            java.util.List<Exit> exits = new java.util.ArrayList<>(4);
            int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] step : steps) {
                for (int reach = 1; reach <= ESCAPE_SEARCH_CHUNKS; reach++) {
                    int cx = chunkX + step[0] * reach;
                    int cz = chunkZ + step[1] * reach;
                    Location probe = new Location(world, (cx << 4) + 8, inside.getY(),
                            (cz << 4) + 8);
                    if (isBannedInside(playerId, probe)) {
                        continue;
                    }
                    double border;
                    double distance;
                    if (step[0] != 0) {
                        border = step[0] > 0 ? cx << 4 : (cx + 1) << 4;
                        distance = Math.abs(border - inside.getX());
                    } else {
                        border = step[1] > 0 ? cz << 4 : (cz + 1) << 4;
                        distance = Math.abs(border - inside.getZ());
                    }
                    exits.add(new Exit(step[0], step[1], border, distance));
                    break;
                }
            }
            exits.sort(java.util.Comparator.comparingDouble(Exit::distance));
            for (Exit exit : exits) {
                for (int distance = currentRetreatDistance(); distance >= 1; distance--) {
                    Location candidate = inside.clone();
                    if (exit.stepX() != 0) {
                        candidate.setX(exit.border() + exit.stepX() * (distance + 0.5D));
                    } else {
                        candidate.setZ(exit.border() + exit.stepZ() * (distance + 0.5D));
                    }
                    Location settled = check.settleFar(player, candidate);
                    if (settled != null && settled.getWorld() != null
                            && usableTarget(playerId, settled)) {
                        return Optional.of(settled);
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Retreat landing for a walked-in deny: the pre-entry position moved away
     * from the crossed side until it sits {@link #RETREAT_DISTANCE} blocks
     * clear of the border, shortened block by block when that spot is
     * unsafe, denied, banned, or unverifiable.
     *
     * <p>The crossed side comes from coordinates alone. A chunk change names
     * the chunk border; inside one chunk (a subland face) the block change
     * names the block face. A purely vertical crossing has no horizontal
     * side to retreat from and answers empty.
     */
    private Optional<Location> retreatTarget(Player player, UUID playerId, Location from,
                                             Location deniedTo) {
        LandingCheck check = this.landing;
        if (check == null || from == null || deniedTo == null) {
            return Optional.empty();
        }
        try {
            World world = from.getWorld();
            World deniedWorld = deniedTo.getWorld();
            if (world == null || deniedWorld == null
                    || !world.getUID().equals(deniedWorld.getUID())) {
                return Optional.empty();
            }
            int fromX = from.getBlockX();
            int fromZ = from.getBlockZ();
            int toX = deniedTo.getBlockX();
            int toZ = deniedTo.getBlockZ();
            int sideX = Integer.signum((fromX >> 4) - (toX >> 4));
            int sideZ = Integer.signum((fromZ >> 4) - (toZ >> 4));
            double borderX;
            double borderZ;
            if (sideX != 0 || sideZ != 0) {
                borderX = sideX > 0 ? ((toX >> 4) + 1) << 4 : (toX >> 4) << 4;
                borderZ = sideZ > 0 ? ((toZ >> 4) + 1) << 4 : (toZ >> 4) << 4;
            } else {
                sideX = Integer.signum(fromX - toX);
                sideZ = Integer.signum(fromZ - toZ);
                if (sideX == 0 && sideZ == 0) {
                    return Optional.empty();
                }
                borderX = sideX > 0 ? toX + 1 : toX;
                borderZ = sideZ > 0 ? toZ + 1 : toZ;
            }
            for (int distance = currentRetreatDistance(); distance >= 1; distance--) {
                Location candidate = from.clone();
                if (sideX != 0) {
                    candidate.setX(retreat(from.getX(), borderX, sideX, distance));
                }
                if (sideZ != 0) {
                    candidate.setZ(retreat(from.getZ(), borderZ, sideZ, distance));
                }
                Location settled = check.settle(player, candidate);
                if (settled != null && settled.getWorld() != null
                        && usableTarget(playerId, settled)) {
                    return Optional.of(settled);
                }
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Centre of the block that leaves {@code distance} whole blocks between
     * it and the border on the player's side, never closer to the border
     * than the player already stood.
     */
    private static double retreat(double current, double border, int side, int distance) {
        return side > 0
                ? Math.max(current, border + distance + 0.5D)
                : Math.min(current, border - distance - 0.5D);
    }

    /** Live push-out window, or the fixed one when no usable tuning answers. */
    private Duration currentCooldown() {
        Tuning live = this.tuning;
        if (live != null) {
            try {
                Duration window = live.pushOutCooldown();
                if (window != null && !window.isNegative()) {
                    return window;
                }
            } catch (RuntimeException unreadable) {
                // Fall through to the fixed window.
            }
        }
        return pushOutCooldown;
    }

    /** Live retreat distance, or the fixed one when no usable tuning answers. */
    private int currentRetreatDistance() {
        Tuning live = this.tuning;
        if (live != null) {
            try {
                int distance = live.retreatDistance();
                if (distance >= 1 && distance <= 16) {
                    return distance;
                }
            } catch (RuntimeException unreadable) {
                // Fall through to the fixed distance.
            }
        }
        return RETREAT_DISTANCE;
    }

    /**
     * Spends the warning slot for this player, so a short push-out window
     * never turns a stuck player into a log line per attempt.
     */
    private boolean mayWarn(UUID playerId) {
        try {
            Instant now = Objects.requireNonNull(clock.now(), "clock must not return null");
            Duration cooldown = currentCooldown();
            Duration window = cooldown.compareTo(WARNING_WINDOW) > 0 ? cooldown : WARNING_WINDOW;
            Instant previous = lastWarned.get(playerId);
            if (previous != null && Duration.between(previous, now).compareTo(window) < 0) {
                return false;
            }
            lastWarned.put(playerId, now);
            return true;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Drops the pass registered for {@code landing} unless a newer one replaced it. */
    private void discardPass(UUID playerId, Location landing) {
        pushOutPasses.computeIfPresent(playerId,
                (id, registered) -> registered == landing ? null : registered);
    }

    /**
     * Claims the single-use pass for a push-out landing. The teleport
     * listener claims this for every arrival <em>before</em> applying the
     * banned-inside origin stop: a rescue teleport departs from the denying
     * area by definition, so only its own validated landing may skip that
     * stop, exactly once. Claiming up front (instead of only inside the ban
     * branch) also spends the pass on ENTRY-deny rescues, whose landing is
     * not banned and would otherwise leave the pass behind. Anything else
     * (unknown player, unrelated destination, replayed arrival) answers
     * {@code false} and stays fully enforced; the destination ENTRY check
     * always runs regardless.
     *
     * @return {@code true} when this arrival is the registered rescue
     *         teleport for the player and the pass is now spent
     */
    boolean consumePushOutPass(UUID playerId, Location to) {
        if (playerId == null || to == null) {
            return false;
        }
        Location registered;
        try {
            registered = pushOutPasses.get(playerId);
        } catch (RuntimeException ex) {
            return false;
        }
        if (!sameLanding(registered, to)) {
            return false;
        }
        return pushOutPasses.remove(playerId, registered);
    }

    /**
     * Drops a pending pass without spending it, for player quit: a pass is
     * only ever meaningful for a teleport that is already in flight, so a
     * disconnecting player starts clean on rejoin. At most one pass exists
     * per player (each push-out overwrites the previous one), so the residue
     * of a teleport that never arrived — cancelled upstream, rewritten by
     * another plugin, lost with the connection — is bounded to one stale
     * entry per online player, and even that entry only ever waives the
     * origin ban stop for its validated landing while the destination ENTRY
     * check still runs.
     */
    void discardPushOutPass(UUID playerId) {
        if (playerId == null) {
            return;
        }
        try {
            pushOutPasses.remove(playerId);
            lastWarned.remove(playerId);
        } catch (RuntimeException ex) {
            // Memory-only map: nothing to recover, never fail the caller.
        }
    }

    /**
     * Whether two positions are the same validated landing: same world and
     * same block. Yaw, pitch, and sub-block precision never distinguish a
     * rescue arrival from its registration.
     */
    private static boolean sameLanding(Location registered, Location arrival) {
        if (registered == null || arrival == null) {
            return false;
        }
        try {
            World expected = registered.getWorld();
            World actual = arrival.getWorld();
            if (expected == null || actual == null
                    || !expected.getUID().equals(actual.getUID())) {
                return false;
            }
            return registered.getBlockX() == arrival.getBlockX()
                    && registered.getBlockY() == arrival.getBlockY()
                    && registered.getBlockZ() == arrival.getBlockZ();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Whether a push-out candidate is usable without loading anything: its
     * chunk is already loaded, ENTRY allows the player there, and the player
     * is not banned there. Anything unverifiable answers {@code false}
     * (fail-closed, cancel-only). A {@code null} ban source skips the inside
     * check, matching {@link #isBannedInside}.
     *
     * <p>Ban-snapshot interaction, kept fail-closed on purpose: when the ban
     * source answers unknown for a known land — the snapshot has not loaded
     * yet, or a reload failed — every candidate on a known land is excluded,
     * including the world spawn when it sits on one. The deny then stands as
     * cancel-only and the player cannot move until the snapshot is
     * available. That freeze is the same posture as movement enforcement
     * itself (an unverifiable ban state must not grant movement, and must
     * not eject the player into it either); it lifts as soon as the first
     * durable load publishes, which normally happens at startup before
     * players can move.
     */
    private boolean usableTarget(UUID playerId, Location candidate) {
        if (candidate == null || candidate.getWorld() == null) {
            return false;
        }
        return chunkLoaded(candidate)
                && entryAllowed(playerId, candidate)
                && !isBannedInside(playerId, candidate);
    }

    private boolean chunkLoaded(Location location) {
        try {
            return chunks.isLoaded(location.getWorld(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean entryAllowed(UUID playerId, Location at) {
        EntryAllowedCheck check = this.entryCheck;
        if (check == null || playerId == null || at == null || at.getWorld() == null) {
            return false;
        }
        try {
            return check.allowed(playerId, at);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * The ENTRY action this adapter enforces through, for call sites that need
     * the action constant without importing the permission API themselves.
     */
    public static ProtectionActionType entryAction() {
        return ProtectionActionType.ENTRY;
    }
}
