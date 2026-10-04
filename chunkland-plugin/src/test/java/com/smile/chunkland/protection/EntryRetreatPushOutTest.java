package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * A walked-in ENTRY deny lands the player a few blocks clear of the border
 * instead of on it, and never somewhere unsafe, denied, or unverified.
 */
class EntryRetreatPushOutTest {

    private static final Instant T0 = Instant.parse("2026-10-04T00:00:00Z");

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        return null;
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "getSpawnLocation" -> new Location((World) proxy, 900, 64, 900);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeWorld";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Player player(UUID id, World world) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlayer";
                    default -> defaultValue(method.getReturnType());
                });
    }

    /** Adapter whose land covers x >= 16 (the chunk at chunkX 1). */
    private static EntryProtectionAdapter adapter(EntryProtectionAdapter.EntryAllowedCheck entry,
            EntryProtectionAdapter.LandingCheck landing, AtomicReference<Location> landed) {
        return new EntryProtectionAdapter(() -> T0, Duration.ofSeconds(3), null,
                (w, x, z) -> true, entry, landing, (p, target) -> {
                    landed.set(target);
                    return true;
                });
    }

    private static final EntryProtectionAdapter.EntryAllowedCheck OUTSIDE_CHUNK_ONE =
            (id, at) -> (at.getBlockX() >> 4) != 1;

    @Test
    void walkedInDenyLandsThreeBlocksClearOfTheChunkBorder() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = adapter(OUTSIDE_CHUNK_ONE, (p, c) -> c, landed);
        Location from = new Location(world, 15.7, 64, 5.5, 90F, 10F);
        Location to = new Location(world, 16.1, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, to));

        Location landing = landed.get();
        assertNotNull(landing);
        assertEquals(12.5D, landing.getX(), 1e-9,
                "three whole blocks (13, 14, 15) must sit between the landing and the border");
        assertEquals(5.5D, landing.getZ(), 1e-9, "the uncrossed axis must not move");
        assertEquals(90F, landing.getYaw(), "the player keeps looking where they looked");
    }

    @Test
    void retreatNeverMovesThePlayerTowardTheBorder() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = adapter(
                (id, at) -> (at.getBlockX() >> 4) != 3, (p, c) -> c, landed);
        // A fast mover refused from far away: already more than three blocks clear.
        Location from = new Location(world, 20.5, 64, 5.5);
        Location to = new Location(world, 50.5, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, to));

        assertEquals(20.5D, landed.get().getX(), 1e-9);
    }

    @Test
    void unsafeFullRetreatFallsBackToAShorterOne() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        // Only the one-block retreat has standing room.
        EntryProtectionAdapter adapter = adapter(OUTSIDE_CHUNK_ONE,
                (p, c) -> c.getBlockX() == 14 ? c : null, landed);
        Location from = new Location(world, 15.7, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 16.1, 64, 5.5)));

        assertEquals(14.5D, landed.get().getX(), 1e-9);
    }

    @Test
    void noStandingRoomAnywhereKeepsThePreEntryPosition() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = adapter(OUTSIDE_CHUNK_ONE, (p, c) -> null, landed);
        Location from = new Location(world, 15.7, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 16.1, 64, 5.5)));

        assertEquals(from, landed.get(), "the plain pre-entry position stays the fallback");
    }

    @Test
    void retreatNeverLandsInsideAnotherDenyingLand() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        // A second denying land covers x <= 13, leaving a two-block corridor.
        EntryProtectionAdapter adapter = adapter(
                (id, at) -> at.getBlockX() == 14 || at.getBlockX() == 15, (p, c) -> c, landed);
        Location from = new Location(world, 15.7, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 16.1, 64, 5.5)));

        assertEquals(14.5D, landed.get().getX(), 1e-9,
                "the retreat shortens until the landing is ENTRY-allowed");
    }

    @Test
    void sublandFaceInsideOneChunkRetreatsFromTheBlockFace() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        // A subland denies z <= 4 inside one chunk; the player came from z = 5.
        EntryProtectionAdapter adapter = adapter(
                (id, at) -> at.getBlockZ() >= 5, (p, c) -> c, landed);
        Location from = new Location(world, 8.5, 64, 5.2);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 8.5, 64, 4.9)));

        assertEquals(8.5D, landed.get().getZ(), 1e-9,
                "three whole blocks (5, 6, 7) must sit between the landing and the face");
        assertEquals(8.5D, landed.get().getX(), 1e-9);
    }

    @Test
    void verticalOnlyCrossingAndTeleportsDoNotRetreat() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0, Duration.ZERO,
                null, (w, x, z) -> true, (id, at) -> true, (p, c) -> {
                    throw new AssertionError("no horizontal side: the probe must not run");
                }, (p, target) -> {
                    landed.set(target);
                    return true;
                });
        Location from = new Location(world, 8.5, 70, 5.5);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 8.5, 69, 5.5)));
        assertEquals(from, landed.get());

        landed.set(null);
        assertTrue(adapter.pushOut(actor, from));
        assertEquals(from, landed.get(), "the two-argument path keeps its plain landing");
    }

    @Test
    void adapterWithoutLandingProbeKeepsThePlainLanding() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = adapter(OUTSIDE_CHUNK_ONE, null, landed);
        Location from = new Location(world, 15.7, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, new Location(world, 16.1, 64, 5.5)));

        assertEquals(from, landed.get());
    }

    @Test
    void retreatLandingStillClaimsItsRescuePass() {
        World world = world(UUID.randomUUID());
        UUID id = UUID.randomUUID();
        Player actor = player(id, world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = adapter(OUTSIDE_CHUNK_ONE, (p, c) -> c, landed);

        assertTrue(adapter.pushOut(actor, new Location(world, 15.7, 64, 5.5),
                new Location(world, 16.1, 64, 5.5)));

        assertTrue(adapter.consumePushOutPass(id, landed.get()),
                "the arrival at the retreat landing must match its registered pass");
        assertFalse(adapter.consumePushOutPass(id, landed.get()), "the pass is single-use");
    }

    // -----------------------------------------------------------------
    // Banned while standing inside: nearest way out, not the world spawn
    // -----------------------------------------------------------------

    /** Adapter whose ban covers chunk X 0..1 on chunk row Z 0 only. */
    private static EntryProtectionAdapter banned(EntryProtectionAdapter.LandingCheck landing,
            AtomicReference<Location> landed) {
        EntryProtectionAdapter.BanLookup bans = (id, worldId, cx, cz) ->
                java.util.Optional.of(cz == 0 && (cx == 0 || cx == 1));
        return new EntryProtectionAdapter(() -> T0, Duration.ofSeconds(3), bans,
                (w, x, z) -> true, (id, at) -> true, landing, (p, target) -> {
                    landed.set(target);
                    return true;
                });
    }

    @Test
    void bannedInsideLandsJustPastTheNearestSideOfTheLand() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = banned(new EntryProtectionAdapter.LandingCheck() {
            @Override
            public Location settle(Player p, Location c) {
                throw new AssertionError("a distant landing must use the tall search");
            }

            @Override
            public Location settleFar(Player p, Location c) {
                return c;
            }
        }, landed);
        // Standing at x = 3 in chunk (0, 0): the west side (x = 0) is 3 blocks away,
        // the north and south sides 8, the east side (x = 32) 29.
        Location inside = new Location(world, 3.0, 64, 8.0);

        assertTrue(adapter.pushOutOfBan(actor, inside));

        assertEquals(-3.5D, landed.get().getX(), 1e-9,
                "three whole blocks (-1, -2, -3) must sit between the landing and the land");
        assertEquals(8.0D, landed.get().getZ(), 1e-9);
        assertTrue(adapter.consumePushOutPass(actor.getUniqueId(), landed.get()),
                "the escape must leave through its own rescue pass");
    }

    @Test
    void blockedNearestSideFallsBackToTheNextNearest() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        // No standing room anywhere west of the land.
        EntryProtectionAdapter adapter = banned((p, c) -> c.getX() < 0 ? null : c, landed);
        Location inside = new Location(world, 3.0, 64, 6.0);

        assertTrue(adapter.pushOutOfBan(actor, inside));

        assertEquals(3.0D, landed.get().getX(), 1e-9);
        assertEquals(-3.5D, landed.get().getZ(), 1e-9,
                "the north side (z = 0) is the next nearest way out");
    }

    @Test
    void noVerifiedWayOutStillFallsBackToTheWorldSpawn() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter unsafe = banned((p, c) -> null, landed);
        assertTrue(unsafe.pushOutOfBan(actor, new Location(world, 3.0, 64, 8.0)));
        assertEquals(900, landed.get().getBlockX(), "spawn stays the last resort");

        landed.set(null);
        EntryProtectionAdapter unprobed = banned(null, landed);
        assertTrue(unprobed.pushOutOfBan(actor, new Location(world, 3.0, 64, 8.0)));
        assertEquals(900, landed.get().getBlockX(),
                "without a standing-room probe the escape is not attempted");
    }

    // -----------------------------------------------------------------
    // Live tuning from the feedback config section
    // -----------------------------------------------------------------

    @Test
    void liveTuningSetsTheRetreatDistanceAndTheWindow() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        java.util.concurrent.atomic.AtomicInteger distance =
                new java.util.concurrent.atomic.AtomicInteger(5);
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(now::get,
                Duration.ofSeconds(3), null, (w, x, z) -> true, OUTSIDE_CHUNK_ONE, (p, c) -> c,
                new EntryProtectionAdapter.Tuning() {
                    @Override
                    public Duration pushOutCooldown() {
                        return Duration.ofMillis(200);
                    }

                    @Override
                    public int retreatDistance() {
                        return distance.get();
                    }
                }, (p, target) -> {
                    landed.set(target);
                    return true;
                });
        Location from = new Location(world, 15.7, 64, 5.5);
        Location to = new Location(world, 16.1, 64, 5.5);

        assertTrue(adapter.pushOut(actor, from, to));
        assertEquals(10.5D, landed.get().getX(), 1e-9, "five blocks clear of x = 16");

        assertFalse(adapter.pushOut(actor, from, to), "still inside the 200 ms window");
        now.set(T0.plusMillis(200));
        distance.set(1);
        assertTrue(adapter.pushOut(actor, from, to), "the live window, not the fixed 3 s");
        assertEquals(14.5D, landed.get().getX(), 1e-9,
                "a changed distance applies to the very next push-out");
    }

    @Test
    void unusableTuningKeepsTheBuiltInValues() {
        World world = world(UUID.randomUUID());
        Player actor = player(UUID.randomUUID(), world);
        AtomicReference<Location> landed = new AtomicReference<>();
        EntryProtectionAdapter adapter = new EntryProtectionAdapter(() -> T0,
                Duration.ofSeconds(3), null, (w, x, z) -> true, OUTSIDE_CHUNK_ONE, (p, c) -> c,
                new EntryProtectionAdapter.Tuning() {
                    @Override
                    public Duration pushOutCooldown() {
                        throw new IllegalStateException("config unavailable");
                    }

                    @Override
                    public int retreatDistance() {
                        return 0;
                    }
                }, (p, target) -> {
                    landed.set(target);
                    return true;
                });

        assertTrue(adapter.pushOut(actor, new Location(world, 15.7, 64, 5.5),
                new Location(world, 16.1, 64, 5.5)));
        assertEquals(12.5D, landed.get().getX(), 1e-9, "the built-in three blocks");
        assertFalse(adapter.pushOut(actor, new Location(world, 15.7, 64, 5.5),
                new Location(world, 16.1, 64, 5.5)), "the built-in window still throttles");
    }

    @Test
    void productionWindowIsShortEnoughToCatchEveryReturnToTheBorder() {
        // Sprint-jumping covers the retreat distance in roughly half a second.
        assertTrue(EntryProtectionAdapter.RETREAT_PUSH_OUT_COOLDOWN.toMillis() <= 500,
                "a longer window leaves the player stuttering on cancelled moves");
        assertEquals(3, EntryProtectionAdapter.RETREAT_DISTANCE);
    }
}
