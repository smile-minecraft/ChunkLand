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
 * stuck client hammering the border costs one teleport per window. The target
 * prefers the pre-entry position (the boundary side) and falls back to the
 * world spawn; each candidate is used only when its chunk is already loaded
 * (verified through {@link ChunkLoadedCheck}) <em>and</em> the player is not
 * ENTRY-denied there (verified through {@link EntryAllowedCheck}). When no
 * candidate passes both checks the deny stands as cancel-only: this adapter
 * never loads a chunk and never sends a player back into a denying land. A
 * missing entry check verifies nothing and therefore allows nothing
 * (fail-closed, cancel-only).
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

    /** Push-out transport. Production teleports on the event thread. */
    @FunctionalInterface
    public interface PushOutSink {
        void teleport(Player player, Location target);
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
     * position when its chunk is loaded and ENTRY allows the player there,
     * else the world spawn under the same two checks, else empty (deny stands
     * as cancel-only). A denied or unverifiable candidate is skipped, never
     * teleported into.
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
            if (from != null && from.getWorld() != null
                    && chunkLoaded(from) && entryAllowed(playerId, from)) {
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
            if (spawn == null || spawn.getWorld() == null
                    || !chunkLoaded(spawn) || !entryAllowed(playerId, spawn)) {
                return Optional.empty();
            }
            return Optional.of(spawn);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Full deny path: resolve a loaded target, spend the throttle slot, and
     * transport. Every failure (no loaded target, throttled, transport threw)
     * yields {@code false} and leaves the cancel in place; nothing here
     * throws for enforcement-path failures.
     *
     * @return {@code true} when the player was actually pushed out
     */
    public boolean pushOut(Player player, Location from) {
        if (player == null) {
            return false;
        }
        Optional<Location> target = pushOutTarget(player, from);
        if (target.isEmpty()) {
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
        try {
            sink.teleport(player, target.get());
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
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
