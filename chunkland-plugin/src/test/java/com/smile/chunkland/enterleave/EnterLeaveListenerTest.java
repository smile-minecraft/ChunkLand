package com.smile.chunkland.enterleave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Listener behaviour over a fake immutable registry: wilderness/land/sub-land
 * transitions, same-chunk silence, preference suppression, fail-closed
 * snapshots and lifecycle cleanup. Zero sleeps; the scheduler is direct.
 */
class EnterLeaveListenerTest {

    private static final UUID WORLD_ID = UUID.randomUUID();
    private static final LandId LAND_A = new LandId(UUID.randomUUID());
    private static final LandId LAND_B = new LandId(UUID.randomUUID());
    private static final SubLandId SUB_A = new SubLandId(UUID.randomUUID());

    private LandRegistryStore store;
    private World world;
    private EnterLeaveTracker tracker;
    private EnterLeavePreferenceService preferences;
    private RecordingSender sender;
    private ChunkLandMessagePipeline pipeline;
    private EnterLeaveListener listener;

    @BeforeEach
    void setUp() throws Exception {
        store = new LandRegistryStore();
        store.publish(registry());
        world = worldProxy();
        tracker = new EnterLeaveTracker();
        preferences = new EnterLeavePreferenceService();
        sender = new RecordingSender();
        pipeline = buildPipeline(sender);
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(pipeline, preferences, PlayerScheduler.direct());
        listener = new EnterLeaveListener(
                store::snapshot, tracker, preferences, null, notifier);
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
    void moveWildernessToLandSendsEnter() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        assertEquals(List.of("Entered Home"), sender.actionBars());
    }

    @Test
    void moveLandToWildernessSendsLeave() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        sender.clear();
        listener.onPlayerMove(move(player, 5, 5, 950, 900));
        assertEquals(List.of("Left Home"), sender.actionBars());
    }

    @Test
    void moveLandToLandSendsLeaveThenEnter() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 20, 5));
        sender.clear();
        listener.onPlayerMove(move(player, 20, 5, 165, 5));
        assertEquals(List.of("Left Home", "Entered Farm"), sender.actionBars());
    }

    @Test
    void sameLandAcrossChunksIsSilent() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move(player, 950, 900, 5, 5));
        sender.clear();
        // Chunk (0,0) to chunk (1,0): same land, no SubLand change.
        listener.onPlayerMove(move(player, 5, 5, 20, 5));
        assertTrue(sender.actionBars().isEmpty());
    }

    @Test
    void moveInsideChunkDownIntoSubLandSendsEnter() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move3d(player, 950, 300, 900, 5, 300, 5));
        sender.clear();
        // Same X/Z chunk (0,0): y=300 sits above Storage, y=64 inside it.
        listener.onPlayerMove(move3d(player, 5, 300, 5, 5, 64, 5));
        assertEquals(List.of("Entered Home › Storage"), sender.actionBars());
    }

    @Test
    void moveInsideChunkUpOutOfSubLandSendsLeave() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move3d(player, 950, 300, 900, 5, 64, 5));
        sender.clear();
        listener.onPlayerMove(move3d(player, 5, 64, 5, 5, 300, 5));
        assertEquals(List.of("Left Home › Storage"), sender.actionBars());
    }

    @Test
    void moveInsideChunkAcrossCuboidBoundarySendsEnterAndLeave() {
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(pipeline, preferences, PlayerScheduler.direct());
        EnterLeaveListener local = new EnterLeaveListener(
                this::cuboidSnapshot, new EnterLeaveTracker(), preferences, null, notifier);
        Player player = playerProxy(UUID.randomUUID());
        // Baseline inside chunk (0,0) and land Home but outside the x=0..7 Nook.
        local.onPlayerMove(move3d(player, 900, 64, 900, 10, 64, 5));
        sender.clear();
        // Same chunk (0,0): x=10 is outside the cuboid, x=5 inside it.
        local.onPlayerMove(move3d(player, 10, 64, 5, 5, 64, 5));
        assertEquals(List.of("Entered Home › Nook"), sender.actionBars());
        local.onPlayerMove(move3d(player, 5, 64, 5, 10, 64, 5));
        assertEquals(
                List.of("Entered Home › Nook", "Left Home › Nook"), sender.actionBars());
    }

    @Test
    void inChunkCrossingUpdatesBaselineWithoutDelayedOrDuplicateNotice() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerMove(move(player, 900, 900, 950, 900));
        listener.onPlayerMove(move3d(player, 950, 300, 900, 5, 300, 5));
        sender.clear();
        listener.onPlayerMove(move3d(player, 5, 300, 5, 5, 64, 5));
        // Chunk (0,0) to chunk (1,0), leaving the SubLand behind: exactly one
        // leave, attached to the SubLand just left, with nothing delayed.
        listener.onPlayerMove(move3d(player, 5, 64, 5, 20, 300, 5));
        assertEquals(
                List.of("Entered Home › Storage", "Left Home › Storage"),
                sender.actionBars());
    }

    @Test
    void sameBlockMoveNeverConsultsSnapshot() {
        AtomicInteger reads = new AtomicInteger();
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(pipeline, preferences, PlayerScheduler.direct());
        EnterLeaveTracker localTracker = new EnterLeaveTracker();
        EnterLeaveListener counting = new EnterLeaveListener(
                () -> {
                    reads.incrementAndGet();
                    return store.snapshot();
                },
                localTracker, preferences, null, notifier);
        Player player = playerProxy(UUID.randomUUID());
        // Looking around inside one block: no snapshot, no prompt, no baseline.
        counting.onPlayerMove(move3d(player, 5, 64, 5, 5, 64, 5));
        assertEquals(0, reads.get(), "same-block movement must not read the snapshot");
        assertTrue(sender.actionBars().isEmpty());
        assertEquals(0, localTracker.sizeForTest());
    }

    @Test
    void inChunkSameCoveringMoveReadsSnapshotOnceAndStaysSilent() {
        AtomicInteger reads = new AtomicInteger();
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(pipeline, preferences, PlayerScheduler.direct());
        EnterLeaveListener counting = new EnterLeaveListener(
                () -> {
                    reads.incrementAndGet();
                    return store.snapshot();
                },
                tracker, preferences, null, notifier);
        Player player = playerProxy(UUID.randomUUID());
        counting.onPlayerMove(move(player, 900, 900, 950, 900));
        counting.onPlayerMove(move(player, 950, 900, 5, 5));
        sender.clear();
        reads.set(0);
        // Same chunk (0,0), y=300 above Storage at both ends: same covering.
        counting.onPlayerMove(move(player, 5, 5, 6, 6));
        assertEquals(1, reads.get(), "in-chunk moves resolve on exactly one snapshot");
        assertTrue(sender.actionBars().isEmpty());
    }

    /**
     * Hot-path budget for notification handling: in-process throughput over
     * the three move shapes (look-around skip, same-covering skip, in-chunk
     * crossing). Prints mean ns/event for the pre/post comparison; the
     * ceiling is deliberately generous (proxy mocks dominate) so this guards
     * against pathological regressions such as I/O on the move path, not
     * against small constant shifts. No chunk loads, SQL, or blocking I/O
     * happen on any of these paths by construction (one snapshot read per
     * inter-block move at most, zero for look-around).
     */
    @Test
    void moveHotPathStaysWithinBudget() {
        Player player = playerProxy(UUID.randomUUID());
        Location lookFrom = new Location(world, 5, 64, 5);
        Location lookTo = new Location(world, 5.3, 64.2, 5.7);
        Location skipFrom = new Location(world, 5, 300, 5);
        Location skipTo = new Location(world, 6, 300, 5);
        Location crossFrom = new Location(world, 5, 300, 5);
        Location crossTo = new Location(world, 5, 64, 5);

        double lookNs = meanMoveNs(player, lookFrom, lookTo);
        double skipNs = meanMoveNs(player, skipFrom, skipTo);
        double crossNs = meanMoveNs(player, crossFrom, crossTo);
        System.out.printf("enterleave-hot-path ns/event: look=%.0f skip=%.0f cross=%.0f%n",
                lookNs, skipNs, crossNs);

        assertTrue(lookNs < 100_000, "look-around path must stay far below 100us/event");
        assertTrue(skipNs < 100_000, "same-covering skip must stay far below 100us/event");
        assertTrue(crossNs < 100_000, "in-chunk crossing must stay far below 100us/event");
    }

    private double meanMoveNs(Player player, Location from, Location to) {
        int warmup = 5_000;
        int measured = 20_000;
        for (int i = 0; i < warmup; i++) {
            listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
        }
        long start = System.nanoTime();
        for (int i = 0; i < measured; i++) {
            listener.onPlayerMove(new PlayerMoveEvent(player, from, to));
        }
        return (double) (System.nanoTime() - start) / measured;
    }

    @Test
    void teleportSubLandChangeSendsLeaveThenEnter() {
        Player player = playerProxy(UUID.randomUUID());
        // y=64 sits inside the Storage SubLand (0..255); y=300 is above it.
        listener.onPlayerTeleport(teleport(player, 900, 64, 900, 5, 64, 5));
        sender.clear();
        listener.onPlayerTeleport(teleport(player, 5, 64, 5, 5, 300, 5));
        assertEquals(List.of("Left Home › Storage"), sender.actionBars());
        listener.onPlayerTeleport(teleport(player, 5, 300, 5, 5, 64, 5));
        assertEquals(List.of("Left Home › Storage", "Entered Home › Storage"), sender.actionBars());
    }

    @Test
    void duplicateTeleportIsSilent() {
        Player player = playerProxy(UUID.randomUUID());
        listener.onPlayerTeleport(teleport(player, 900, 64, 900, 5, 64, 5));
        sender.clear();
        listener.onPlayerTeleport(teleport(player, 5, 64, 5, 6, 64, 6));
        assertTrue(sender.actionBars().isEmpty());
    }

    @Test
    void cancelledMoveIsIgnored() {
        Player player = playerProxy(UUID.randomUUID());
        PlayerMoveEvent event = move(player, 900, 900, 5, 5);
        event.setCancelled(true);
        listener.onPlayerMove(event);
        assertTrue(sender.actionBars().isEmpty());
        assertEquals(0, tracker.sizeForTest());
    }

    @Test
    void disabledPlayerReceivesNothing() {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        preferences.setForTest(id, false);
        listener.onPlayerMove(move(player, 900, 900, 5, 5));
        listener.onPlayerMove(move(player, 5, 5, 900, 900));
        assertTrue(sender.actionBars().isEmpty());
    }

    @Test
    void unreadySnapshotFailsClosedWithoutTrackerUpdate() {
        EnterLeaveNotifier notifier =
                new EnterLeaveNotifier(pipeline, preferences, PlayerScheduler.direct());
        EnterLeaveTracker localTracker = new EnterLeaveTracker();
        EnterLeaveListener unready = new EnterLeaveListener(
                () -> null, localTracker, preferences, null, notifier);
        Player player = playerProxy(UUID.randomUUID());
        unready.onPlayerMove(move(player, 900, 900, 5, 5));
        assertTrue(sender.actionBars().isEmpty());
        assertEquals(0, localTracker.sizeForTest());
    }

    @Test
    void throwingSchedulerDropsFailClosed() {
        EnterLeaveNotifier strict = new EnterLeaveNotifier(pipeline, preferences,
                (player, task) -> {
                    throw new IllegalStateException("retired");
                });
        EnterLeaveListener strictListener = new EnterLeaveListener(
                store::snapshot, new EnterLeaveTracker(), preferences, null, strict);
        Player player = playerProxy(UUID.randomUUID());
        // Must not throw; the prompt is dropped.
        strictListener.onPlayerMove(move(player, 900, 900, 5, 5));
        strictListener.onPlayerMove(move(player, 5, 5, 900, 900));
        assertTrue(sender.actionBars().isEmpty());
    }

    @Test
    void quitClearsTrackerAndPreference() {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        listener.onPlayerMove(move(player, 900, 900, 5, 5));
        preferences.setForTest(id, false);
        listener.onPlayerQuit(id);
        assertEquals(0, tracker.sizeForTest());
        assertTrue(preferences.enabled(id), "quit must forget the switch back to default");
    }

    @Test
    void joinLoadsSwitchAndSeedsBaselineSilently() throws Exception {
        UUID id = UUID.randomUUID();
        Player player = playerProxy(id);
        assertEquals(true, listener.onPlayerAvailable(id).toCompletableFuture().get());
        assertTrue(preferences.enabled(id));
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private PlayerMoveEvent move(Player player, int fromX, int fromZ, int toX, int toZ) {
        // y=300 sits above the Storage SubLand (0..255), so plain moves
        // resolve to the parent land without SubLand context.
        return new PlayerMoveEvent(player,
                new Location(world, fromX, 300, fromZ),
                new Location(world, toX, 300, toZ));
    }

    private PlayerTeleportEvent teleport(Player player,
            int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return new PlayerTeleportEvent(player,
                new Location(world, fromX, fromY, fromZ),
                new Location(world, toX, toY, toZ));
    }

    private PlayerMoveEvent move3d(Player player,
            int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return new PlayerMoveEvent(player,
                new Location(world, fromX, fromY, fromZ),
                new Location(world, toX, toY, toZ));
    }

    private LandRegistry cuboidSnapshot() {
        Instant now = Instant.now();
        ChunkKey chunk = new ChunkKey(WORLD_ID, 0, 0);
        SubLandSnapshot nook = new SubLandSnapshot(SUB_A, LAND_A, "Nook",
                new Cuboid(0, 0, 0, 7, 255, 15), WORLD_ID);
        LandSnapshot home = new LandSnapshot(LAND_A, "Home", "home",
                OwnerRef.player(UUID.randomUUID()), WORLD_ID,
                Set.of(chunk), List.of(nook), 0, 0, now, now);
        return LandRegistry.from(List.of(home));
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

    private static ChunkLandMessagePipeline buildPipeline(RecordingSender sender) throws Exception {
        ChunkLandMessagePipeline.LangProvider lang =
                (locale, key) -> java.util.Optional.ofNullable(OptionalHolder.templates().get(key));
        ChunkLandMessagePipeline.MessageParser parser = (template, vars) -> {
            String rendered = template;
            if (vars != null) {
                for (Map.Entry<String, Object> entry : vars.entrySet()) {
                    rendered = rendered.replace("<" + entry.getKey() + ">",
                            String.valueOf(entry.getValue()));
                }
            }
            rendered = rendered.replaceAll("<[^>]+>", "");
            return Component.text(rendered);
        };
        Constructor<ChunkLandMessagePipeline> ctor = ChunkLandMessagePipeline.class.getDeclaredConstructor(
                ChunkLandMessagePipeline.PipelineSender.class,
                ChunkLandMessagePipeline.MessageParser.class,
                ChunkLandMessagePipeline.LangProvider.class,
                com.smile.acelib.bedrock.BedrockService.class,
                Locale.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sender, parser, lang, null, Locale.US);
    }

    private static final class OptionalHolder {
        static Map<String, String> templates() {
            Map<String, String> templates = new HashMap<>();
            templates.put("land.enter.message", "<green>Entered <land_name></green>");
            templates.put("land.leave.message", "<gray>Left <land_name></gray>");
            templates.put("land.subland.enter", "<green>Entered <land_name> › <sub_name></green>");
            templates.put("land.subland.leave", "<gray>Left <land_name> › <sub_name></gray>");
            return templates;
        }
    }

    private static final class RecordingSender implements ChunkLandMessagePipeline.PipelineSender {
        private final List<String> actionBars = new ArrayList<>();

        @Override
        public void sendChat(Player player, Component message) {
        }

        @Override
        public void sendChatWithFallback(Player player, Component message, Locale locale) {
        }

        @Override
        public void sendActionBar(Player player, Component message) {
            actionBars.add(plain(message));
        }

        @Override
        public void sendActionBarWithFallback(Player player, Component message, Locale locale) {
            actionBars.add(plain(message));
        }

        @Override
        public void sendTitle(Player player, Component title, Component subtitle) {
        }

        @Override
        public void sendTitleWithFallback(Player player, Component title, Component subtitle, Locale locale) {
        }

        @Override
        public void broadcastWithFallback(Component message, Locale locale) {
        }

        List<String> actionBars() {
            return List.copyOf(actionBars);
        }

        void clear() {
            actionBars.clear();
        }

        private static String plain(Component message) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(message);
        }
    }
}
