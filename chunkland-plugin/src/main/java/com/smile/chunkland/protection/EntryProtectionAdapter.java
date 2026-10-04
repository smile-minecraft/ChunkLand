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
    private final PushOutSink sink;
    private final ConcurrentMap<UUID, Instant> lastPushOut = new ConcurrentHashMap<>();
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
        this.clock = Objects.requireNonNull(clock, "clock");
        Duration interval = pushOutCooldown == null ? DEFAULT_PUSH_OUT_COOLDOWN : pushOutCooldown;
        if (interval.isNegative()) {
            throw new IllegalArgumentException("pushOutCooldown must not be negative: " + interval);
        }
        this.pushOutCooldown = interval;
        this.bans = bans;
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.entryCheck = entryCheck;
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
        while (true) {
            Instant previous = lastPushOut.get(playerId);
            if (previous != null && Duration.between(previous, now).compareTo(pushOutCooldown) < 0) {
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
        Optional<Location> target = pushOutTarget(player, from);
        if (target.isEmpty()) {
            LOG.warning("ChunkLand push-out found no push-out target for a denied move: "
                    + "no candidate chunk is loaded, ENTRY-allowed, and ban-free, "
                    + "so the deny stands as cancel-only");
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
            LOG.warning("ChunkLand push-out transport refused the landing for a denied move: "
                    + "the platform rejected the ejection, so the deny stands as cancel-only");
        }
        return accepted;
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
