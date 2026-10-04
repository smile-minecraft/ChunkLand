package com.smile.chunkland.enterleave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.LandEnterEvent;
import com.smile.chunkland.api.event.LandLeaveEvent;
import com.smile.chunkland.api.event.SubLandEnterEvent;
import com.smile.chunkland.api.event.SubLandLeaveEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Public enter/leave events: fired exactly once per actual tracker
 * transition (never on baseline or same-boundary moves), carrying only
 * identities; the player prompt path still hops through the injected
 * {@link PlayerScheduler}; a throwing listener never breaks movement.
 */
class EnterLeavePublicEventTest {

    private static final UUID WORLD_ID = UUID.randomUUID();
    private static final LandId LAND_A = new LandId(UUID.randomUUID());
    private static final LandId LAND_B = new LandId(UUID.randomUUID());
    private static final SubLandId SUB_A = new SubLandId(UUID.randomUUID());

    private LandRegistryStore store;
    private World world;
    private EnterLeaveTracker tracker;
    private EnterLeavePreferenceService preferences;
    private PublicEventBus bus;
    private List<Object> fired;
    private EnterLeaveListener listener;

    @BeforeEach
    void setUp() {
        store = new LandRegistryStore();
        store.publish(registry());
        world = worldProxy();
        tracker = new EnterLeaveTracker();
        preferences = new EnterLeavePreferenceService();
        bus = new PublicEventBus();
        fired = new ArrayList<>();
        bus.register(Object.class, event -> {
            synchronized (fired) {
                fired.add(event);
            }
        });
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(null, preferences, PlayerScheduler.direct());
        listener = new EnterLeaveListener(
                store::snapshot, tracker, preferences, null, notifier,
                PublicEvents.create(bus, null));
    }

    private static LandRegistry registry() {
        Instant now = Instant.now();
        ChunkKey homeChunk0 = new ChunkKey(WORLD_ID, 0, 0);
        ChunkKey homeChunk1 = new ChunkKey(WORLD_ID, 1, 0);
        SubLandSnapshot sub = new SubLandSnapshot(SUB_A, LAND_A, "Storage", 0, 255,
                Set.of(homeChunk0));
        LandSnapshot home = new LandSnapshot(LAND_A, "Home", "home",
                OwnerRef.player(UUID.randomUUID()), WORLD_ID,
                Set.of(homeChunk0, homeChunk1), List.of(sub), 0, 0, now, now);
        LandSnapshot farm = new LandSnapshot(LAND_B, "Farm", "farm",
                OwnerRef.player(UUID.randomUUID()), WORLD_ID,
                Set.of(new ChunkKey(WORLD_ID, 10, 0)), List.of(), 0, 0, now, now);
        return LandRegistry.from(List.of(home, farm));
    }

    @Test
    void wildernessToLandEmitsOneEnter() {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        assertTrue(fired.isEmpty(), "first observation is a silent baseline");
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof LandEnterEvent);
        LandEnterEvent enter = (LandEnterEvent) fired.get(0);
        assertEquals(id, enter.playerId());
        assertEquals(LAND_A, enter.landId());
        assertEquals(WORLD_ID, enter.worldId());
    }

    @Test
    void landToLandEmitsLeaveThenEnter() {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        fired.clear();
        listener.onPlayerMove(move(player, 5, 5, 165, 5));
        assertEquals(2, fired.size());
        assertTrue(fired.get(0) instanceof LandLeaveEvent);
        assertTrue(fired.get(1) instanceof LandEnterEvent);
        assertEquals(LAND_A, ((LandLeaveEvent) fired.get(0)).landId());
        assertEquals(LAND_B, ((LandEnterEvent) fired.get(1)).landId());
        assertEquals(id, ((LandLeaveEvent) fired.get(0)).playerId());
    }

    @Test
    void landToWildernessEmitsOneLeave() {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        fired.clear();
        listener.onPlayerMove(move(player, 5, 5, 950, 900));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof LandLeaveEvent);
        assertEquals(LAND_A, ((LandLeaveEvent) fired.get(0)).landId());
    }

    @Test
    void sameBoundaryMoveEmitsNothing() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        fired.clear();
        listener.onPlayerMove(move(player, 5, 5, 20, 5));
        assertTrue(fired.isEmpty());
    }

    @Test
    void sublandTransitionsEmitSubEvents() {
        Player player = playerProxy(UUID.randomUUID());
        // Baseline inside the parent land but outside the SubLand: silent.
        listener.onPlayerTeleport(teleport(player, 900, 64, 900, 20, 300, 5));
        assertTrue(fired.isEmpty());
        listener.onPlayerTeleport(teleport(player, 20, 300, 5, 5, 64, 5));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof SubLandEnterEvent);
        SubLandEnterEvent enter = (SubLandEnterEvent) fired.get(0);
        assertEquals(LAND_A, enter.landId());
        assertEquals(SUB_A, enter.subLandId());
        fired.clear();
        listener.onPlayerTeleport(teleport(player, 5, 64, 5, 20, 300, 5));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof SubLandLeaveEvent);
        SubLandLeaveEvent leave = (SubLandLeaveEvent) fired.get(0);
        assertEquals(LAND_A, leave.landId());
        assertEquals(SUB_A, leave.subLandId());
    }

    @Test
    void inChunkSubLandCrossingEmitsSubEnterThenSubLeave() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move3d(player, 950, 300, 900, 5, 300, 5));
        fired.clear();
        // Same X/Z chunk (0,0): y=300 sits above Storage, y=64 inside it.
        listener.onPlayerMove(move3d(player, 5, 300, 5, 5, 64, 5));
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof SubLandEnterEvent);
        assertEquals(SUB_A, ((SubLandEnterEvent) fired.get(0)).subLandId());
        listener.onPlayerMove(move3d(player, 5, 64, 5, 5, 300, 5));
        assertEquals(2, fired.size());
        assertTrue(fired.get(1) instanceof SubLandLeaveEvent);
        SubLandLeaveEvent leave = (SubLandLeaveEvent) fired.get(1);
        assertEquals(LAND_A, leave.landId());
        assertEquals(SUB_A, leave.subLandId());
        // The baseline already caught up: leaving the chunk afterwards emits
        // exactly the land-level leave, with no delayed SubLand echo.
        listener.onPlayerMove(move3d(player, 5, 300, 5, 20, 300, 5));
        assertEquals(2, fired.size());
    }

    @Test
    void throwingListenerNeverBreaksMovement() {
        bus.register(Object.class, event -> {
            throw new IllegalStateException("broken listener");
        });
        Player player = playerProxy(UUID.randomUUID());
        // Must not throw; the tracker still advances past the baseline.
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        assertEquals(1, tracker.sizeForTest());
    }

    @Test
    void promptPathStillHopsThroughPlayerScheduler() {
        AtomicInteger hops = new AtomicInteger();
        EnterLeaveNotifier hopping = new EnterLeaveNotifier(
                null, preferences, (player, task) -> hops.incrementAndGet());
        EnterLeaveListener hoppingListener = new EnterLeaveListener(
                store::snapshot, new EnterLeaveTracker(), preferences, null, hopping,
                PublicEvents.create(bus, null));
        Player player = playerProxy(UUID.randomUUID());
        hoppingListener.onPlayerMove(move(player, 900, 900, 950, 900));
        hoppingListener.onPlayerMove(move(player, 950, 900, 5, 5));
        assertEquals(0, hops.get(), "null pipeline schedules nothing");
        assertEquals(1, fired.size(), "public event fires even without a pipeline");
    }

    @Test
    void eventsFireWhenSchedulerItselfThrows() {
        EnterLeaveNotifier strict = new EnterLeaveNotifier(null, preferences,
                (player, task) -> {
                    throw new IllegalStateException("retired");
                });
        EnterLeaveListener strictListener = new EnterLeaveListener(
                store::snapshot, new EnterLeaveTracker(), preferences, null, strict,
                PublicEvents.create(bus, null));
        Player player = playerProxy(UUID.randomUUID());
        strictListener.onPlayerMove(move(player, 900, 900, 950, 900));
        strictListener.onPlayerMove(move(player, 950, 900, 5, 5));
        assertEquals(1, fired.size(), "emission never depends on the player scheduler hop");
        assertTrue(fired.get(0) instanceof LandEnterEvent);
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private PlayerMoveEvent move(Player player, int fromX, int fromZ, int toX, int toZ) {
        return new PlayerMoveEvent(player,
                new Location(world, fromX, 300, fromZ),
                new Location(world, toX, 300, toZ));
    }

    private PlayerMoveEvent move3d(Player player,
            int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return new PlayerMoveEvent(player,
                new Location(world, fromX, fromY, fromZ),
                new Location(world, toX, toY, toZ));
    }

    private PlayerTeleportEvent teleport(Player player,
            int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return new PlayerTeleportEvent(player,
                new Location(world, fromX, fromY, fromZ),
                new Location(world, toX, toY, toZ));
    }

    private static World worldProxy() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return WORLD_ID;
                        case "getName": return "world";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private static Player playerProxy(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "locale": return Locale.US;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }
}
