package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import com.smile.chunkland.selection.OccupiedPreviewController;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.selection.SelectionLandBoundaryLookup;
import com.smile.chunkland.selection.SelectionLandContext;
import com.smile.chunkland.selection.SelectionLandIndex;
import com.smile.chunkland.selection.SelectionLandLookup;
import com.smile.chunkland.selection.SelectionLifecycleListener;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotification;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for wand clicks driving the selection flow.
 *
 * <p>Either mouse button (block interact, main hand) records a corner: the
 * first valid click sets the first corner, the second valid click sets the
 * other corner and analyses both as one chunk rectangle so {@code /land
 * claim} can read the covered chunks. A further click after a completed
 * rectangle starts a fresh selection instead of extending the old one.
 * BlockDamage/BlockBreak stay safety-cancel only so Survival double events
 * never record a second corner. Visualization follows the session manager,
 * which already owns the render lifecycle.
 */
class SelectionWandClickHandlerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222223");
    private static final LandId OWN_LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final LandId OTHER_LAND = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private static final LandId SERVER_LAND = new LandId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private static final class RecordingVisualization implements SelectionVisualizationTaskController {
        final List<SelectionSession> starts = new ArrayList<>();
        final List<SelectionSession> refreshes = new ArrayList<>();
        final Set<UUID> stopped = new HashSet<>();

        @Override
        public void stop(UUID playerId) {
            stopped.add(playerId);
        }

        @Override
        public void start(SelectionSession session) {
            starts.add(session);
        }

        @Override
        public void refresh(SelectionSession session) {
            refreshes.add(session);
        }
    }

    private static final class Harness {
        final Map<Long, SelectionLandContext> lands = new LinkedHashMap<>();
        final Map<LandId, Long> revisions = new HashMap<>();
        final Map<LandId, Set<ChunkKey>> boundaries = new LinkedHashMap<>();
        final RecordingVisualization visualization = new RecordingVisualization();
        final RecordingPreview preview = new RecordingPreview();
        final List<Step> feedback = new ArrayList<>();
        final List<SelectionEndReason> endReasons = new ArrayList<>();
        volatile boolean registryReady = true;
        final SelectionClock clock = () -> NOW;
        final SelectionLandLookup lookup = (world, chunkX, chunkZ) -> {
            if (!registryReady) {
                throw new IllegalStateException("land registry is not hydrated");
            }
            if (!WORLD_ID.equals(world)) {
                return null;
            }
            return lands.get(WorldChunkIndex.pack(chunkX, chunkZ));
        };
        final SelectionLandIndex index = (world, packed) -> {
            SelectionLandContext context = lands.get(packed);
            return context == null ? null : context.landId();
        };
        final SelectionLandBoundaryLookup boundaryLookup = landId -> {
            if (!registryReady) {
                return Optional.empty();
            }
            Set<ChunkKey> chunks = boundaries.get(landId);
            return chunks == null ? Optional.empty() : Optional.of(chunks);
        };
        final SelectionStructureRevisionLookup revisionsLookup = landId -> {
            Long value = revisions.get(landId);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        };
        final SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                visualization,
                notification -> endReasons.add(notification.reason()),
                (SelectionClock) () -> NOW,
                () -> Duration.ofMinutes(10),
                uuid -> Optional.empty(),
                revisionsLookup);
        final com.smile.chunkland.selection.SelectionEditService edits =
                new com.smile.chunkland.selection.SelectionEditService(
                        new LimitSettings(5, 256, 128, 16, 32, 1024),
                        index);

        /** One recorded guidance prompt: the step plus the vars handed to the message pipeline. */
        record Step(WandFeedback.Kind kind, Map<String, Object> vars) {}

        void ownLand(LandId landId, long revision, int chunkX, int chunkZ) {
            ownLand(landId, revision, Set.of(new ChunkKey(WORLD_ID, chunkX, chunkZ)));
        }

        void ownLand(LandId landId, long revision, Set<ChunkKey> chunks) {
            revisions.put(landId, revision);
            boundaries.put(landId, Set.copyOf(chunks));
            for (ChunkKey chunk : chunks) {
                lands.put(WorldChunkIndex.pack(chunk.chunkX(), chunk.chunkZ()),
                        new SelectionLandContext(landId, OwnerRef.player(PLAYER_ID), revision));
            }
        }

        void otherPlayerLand(LandId landId, long revision, int chunkX, int chunkZ) {
            revisions.put(landId, revision);
            ChunkKey chunk = new ChunkKey(WORLD_ID, chunkX, chunkZ);
            boundaries.put(landId, Set.of(chunk));
            lands.put(WorldChunkIndex.pack(chunkX, chunkZ),
                    new SelectionLandContext(landId, OwnerRef.player(UUID.randomUUID()), revision));
        }

        void serverLand(LandId landId, long revision, int chunkX, int chunkZ) {
            revisions.put(landId, revision);
            ChunkKey chunk = new ChunkKey(WORLD_ID, chunkX, chunkZ);
            boundaries.put(landId, Set.of(chunk));
            lands.put(WorldChunkIndex.pack(chunkX, chunkZ),
                    new SelectionLandContext(landId, OwnerRef.server(), revision));
        }

        SelectionWandClickHandler handler() {
            return new SelectionWandClickHandler(
                    manager, () -> edits, clock, (player, kind, vars) -> feedback.add(new Step(kind, vars)),
                    lookup, boundaryLookup, preview);
        }

        List<WandFeedback.Kind> feedbackKinds() {
            return feedback.stream().map(Step::kind).toList();
        }

        WandSafetyListener listener() {
            return new WandSafetyListener(handler());
        }
    }

    private static final class RecordingPreview implements OccupiedPreviewController {
        record Call(UUID playerId, Set<ChunkKey> chunks, double planeY) {
        }

        final List<Call> shown = new ArrayList<>();
        final List<UUID> stopped = new ArrayList<>();

        @Override
        public void show(UUID playerId, Set<ChunkKey> chunks, double planeY) {
            shown.add(new Call(playerId, Set.copyOf(chunks), planeY));
        }

        @Override
        public void stop(UUID playerId) {
            stopped.add(playerId);
        }
    }

    private static FakeItemStack wandStack() {
        var pdc = TestWandHelpers.fakePdc();
        var meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        return new FakeItemStack(meta);
    }

    private static FakeItemStack plainStack() {
        return new FakeItemStack(TestWandHelpers.fakeMeta(TestWandHelpers.fakePdc()));
    }

    private static Player playerWith(FakeItemStack mainHand) {
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItemInMainHand")) return mainHand;
                    if (method.getName().equals("getItemInOffHand")) return null;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        return playerWithInventory(inv);
    }

    /** Player whose hotbar slot 0 and slot 1 (and off hand) are stubbed per event reads. */
    private static Player playerWithSlots(FakeItemStack slot0, FakeItemStack slot1) {
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(
                PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItem")) {
                        int slot = (Integer) args[0];
                        if (slot == 0) return slot0;
                        if (slot == 1) return slot1;
                        return null;
                    }
                    if (method.getName().equals("getItemInMainHand")) return slot0;
                    if (method.getName().equals("getItemInOffHand")) return slot1;
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
        return playerWithInventory(inv);
    }

    private static Player playerWithInventory(PlayerInventory inv) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId" -> { return PLAYER_ID; }
                        case "getInventory" -> { return inv; }
                        case "getName" -> { return "TestPlayer"; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                        }
                    }
                });
    }

    private static World fakeWorld() {
        return fakeWorld(WORLD_ID);
    }

    private static World fakeWorld(UUID worldId) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID" -> { return worldId; }
                        case "getName" -> { return "world"; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "toString" -> { return "FakeWorld"; }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                        }
                    }
                });
    }

    private static Block blockAt(World world, int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld" -> { return world; }
                        case "getX" -> { return x; }
                        case "getY" -> { return y; }
                        case "getZ" -> { return z; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "toString" -> { return "FakeBlock(" + x + "," + y + "," + z + ")"; }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                        }
                    }
                });
    }

    private static BlockDamageEvent damageEvent(Player player, Block block, FakeItemStack item) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockDamageEvent ev = (BlockDamageEvent) unsafe.allocateInstance(BlockDamageEvent.class);
        var fBlock = org.bukkit.event.block.BlockEvent.class.getDeclaredField("block");
        fBlock.setAccessible(true);
        fBlock.set(ev, block);
        var fPlayer = BlockDamageEvent.class.getDeclaredField("player");
        fPlayer.setAccessible(true);
        fPlayer.set(ev, player);
        var fItem = BlockDamageEvent.class.getDeclaredField("itemInHand");
        fItem.setAccessible(true);
        fItem.set(ev, item);
        var fInsta = BlockDamageEvent.class.getDeclaredField("instaBreak");
        fInsta.setAccessible(true);
        fInsta.set(ev, false);
        var fCancelled = BlockDamageEvent.class.getDeclaredField("cancelled");
        fCancelled.setAccessible(true);
        fCancelled.set(ev, false);
        try {
            var fFace = BlockDamageEvent.class.getDeclaredField("blockFace");
            fFace.setAccessible(true);
            fFace.set(ev, BlockFace.NORTH);
        } catch (Exception ignored) {
        }
        return ev;
    }

    private static BlockBreakEvent breakEvent(Block block, Player player) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        BlockBreakEvent ev = (BlockBreakEvent) unsafe.allocateInstance(BlockBreakEvent.class);
        var fBlock = org.bukkit.event.block.BlockEvent.class.getDeclaredField("block");
        fBlock.setAccessible(true);
        fBlock.set(ev, block);
        var fPlayer = BlockBreakEvent.class.getDeclaredField("player");
        fPlayer.setAccessible(true);
        fPlayer.set(ev, player);
        var fCancelled = BlockBreakEvent.class.getDeclaredField("cancelled");
        fCancelled.setAccessible(true);
        fCancelled.set(ev, false);
        return ev;
    }

    @Test
    void listenerExposesRightClickHandler() throws Exception {
        var method = WandSafetyListener.class.getDeclaredMethod("onPlayerInteract", PlayerInteractEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertNotNull(handler, "right click must be a registered event handler");
        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    @Test
    void leftClickCreatesSessionWithFirstCorner() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        Block block = blockAt(fakeWorld(), 0, 64, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(player, block, BlockFace.NORTH, false));

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent(), "left click with the wand must open a selection session");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertTrue(session.get().pointB().isEmpty(), "second corner stays empty until right click");
        assertEquals(1, harness.visualization.starts.size(), "manager must start visualization");
        assertEquals(1, harness.visualization.refreshes.size(), "first corner must refresh visualization");
    }

    @Test
    void rightClickAfterLeftCompletesRectangleForClaim() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();

        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        harness.handler().onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), session.get().pointB());
        assertEquals(2, session.get().selectedChunks().size(), "two adjacent chunks must be claimable");
        assertEquals(2, harness.visualization.refreshes.size(), "second corner must refresh visualization");
    }

    @Test
    void rightClickFirstRecordsFirstCorner() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 16, 64, 0), BlockFace.NORTH);

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent(), "right click with the wand must open a selection session");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), session.get().pointA());
        assertTrue(session.get().pointB().isEmpty(), "second corner stays empty until the next click");
        assertTrue(session.get().selectedChunks().isEmpty(), "one corner alone selects nothing");
    }

    @Test
    void leftClickTwiceCompletesRectangle() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();

        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 16, 64, 0), BlockFace.NORTH, false));

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), session.get().pointB());
        assertEquals(2, session.get().selectedChunks().size(), "same-button clicks must still complete the rectangle");
    }

    @Test
    void rightClickThenLeftClickCompletesRectangle() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();

        harness.handler().onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);
        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), session.get().pointA());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointB());
        assertEquals(2, session.get().selectedChunks().size(), "reversed button order must complete the rectangle");
    }

    @Test
    void thirdClickResizesKeepingTheFirstCornerAndTheSameSession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        var handler = harness.handler();

        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);
        SelectionSession completed = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(2, completed.selectedChunks().size());
        assertEquals(2, harness.visualization.refreshes.size());

        // A third click adjusts the second corner in place: the first corner and
        // the session generation stay identical, only pointB and the rectangle move.
        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 32, 64, 0), BlockFace.NORTH, false));

        Optional<SelectionSession> resized = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(resized.isPresent());
        assertEquals(completed.sessionGeneration(), resized.get().sessionGeneration(),
                "resize must mutate the existing session, not start a new one");
        assertSame(completed.pointA().orElseThrow(), resized.get().pointA().orElseThrow(),
                "first corner identity must be preserved across a resize");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 32, 64, 0)), resized.get().pointB(),
                "the click moves the second corner");
        assertEquals(3, resized.get().selectedChunks().size(), "the rectangle is recomputed for the new point");
        assertTrue(resized.get().selectionRevision() > completed.selectionRevision());
        assertEquals(3, harness.visualization.refreshes.size(), "each accepted resize refreshes visualization");

        // Resizing back down keeps the same first corner and shrinks the rectangle.
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);
        SelectionSession shrunk = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(completed.sessionGeneration(), shrunk.sessionGeneration());
        assertEquals(completed.pointA(), shrunk.pointA());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), shrunk.pointB());
        assertEquals(2, shrunk.selectedChunks().size());
    }

    @Test
    void acceptedClicksEmitOneGuidancePerStep() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        var handler = harness.handler();

        // Right first still emits FIRST; left completes SECOND; the next click RESIZED.
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);
        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 32, 64, 0), BlockFace.NORTH, false));

        assertEquals(
                List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.SECOND_POINT, WandFeedback.Kind.RESIZED),
                harness.feedbackKinds(),
                "each accepted click emits exactly one guidance step");
        assertEquals(Map.of(), harness.feedback.get(0).vars(),
                "the first corner has no size to announce yet");
        assertTrue(harness.feedback.get(1).vars().containsKey("width")
                        && harness.feedback.get(1).vars().containsKey("height"),
                "the second point must hand the accepted size vars to the pipeline");
        assertTrue(harness.feedback.get(2).vars().containsKey("width")
                        && harness.feedback.get(2).vars().containsKey("height"),
                "a resize must hand the accepted size vars to the pipeline");
    }

    @Test
    void secondPointAndResizePromptsCarryAcceptedChunkDimensions() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        var handler = harness.handler();

        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        // Chunks (0,0)-(1,2): a 2-wide, 3-deep rectangle.
        handler.onWandRightClick(player, blockAt(world, 31, 64, 47), BlockFace.NORTH);
        // Resizing to chunks (0,0)-(1,1): a 2×2 rectangle, same first corner.
        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 16, 64, 16), BlockFace.NORTH, false));

        assertEquals(3, harness.feedback.size());
        assertEquals(Map.of("width", 2, "height", 3), harness.feedback.get(1).vars(),
                "the second point must announce the accepted 2×3 chunk rectangle");
        assertEquals(Map.of("width", 2, "height", 2), harness.feedback.get(2).vars(),
                "the resize must announce the accepted 2×2 chunk rectangle");
    }

    @Test
    void singleChunkSelectionAnnouncesOneByOne() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        var handler = harness.handler();

        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 15, 64, 15), BlockFace.NORTH);

        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.SECOND_POINT),
                harness.feedbackKinds());
        assertEquals(Map.of("width", 1, "height", 1), harness.feedback.get(1).vars(),
                "both corners inside one chunk must announce a 1×1 selection");
    }

    @Test
    void switchingAwayFromWandClearsRangeAndReturningGuidesReset() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        FakeItemStack plain = plainStack();
        Player player = playerWithSlots(wand, plain);
        World world = fakeWorld();
        var handler = harness.handler();
        WandSafetyListener listener = new WandSafetyListener(handler);

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), null, false));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent());
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT), harness.feedbackKinds());

        // Slot 0 (wand) -> slot 1 (plain): the range and its particles must go.
        listener.onPlayerItemHeld(new PlayerItemHeldEvent(player, 0, 1));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(), "non-wand main hand must drop the range");
        assertTrue(harness.visualization.stopped.contains(PLAYER_ID), "particles stop with the range");
        assertEquals(
                List.of(SelectionEndReason.ITEM_CHANGED),
                harness.endReasons,
                "the end notifier sees exactly one ITEM_CHANGED reason");
        assertEquals(
                List.of(WandFeedback.Kind.FIRST_POINT),
                harness.feedbackKinds(),
                "unequip is an end-reason message, not a click-feedback step");

        // Slot 1 (plain) -> slot 0 (wand): guidance only, no session is reopened.
        listener.onPlayerItemHeld(new PlayerItemHeldEvent(player, 1, 0));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.RESET), harness.feedbackKinds());
    }

    @Test
    void movingBetweenTwoWandSlotsStaysSilent() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWithSlots(wand, wand);
        var handler = harness.handler();
        WandSafetyListener listener = new WandSafetyListener(handler);

        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        SelectionSession live = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        harness.feedback.clear();
        harness.endReasons.clear();

        listener.onPlayerItemHeld(new PlayerItemHeldEvent(player, 0, 1));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent(), "wand -> wand is not a transition");
        assertEquals(live.sessionGeneration(), harness.manager.sessionFor(PLAYER_ID).orElseThrow().sessionGeneration());
        assertTrue(harness.endReasons.isEmpty());
        assertTrue(harness.feedback.isEmpty());
    }

    @Test
    void swappingWandIntoTheOffHandClearsRange() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        FakeItemStack plain = plainStack();
        // Main hand holds the wand, off hand a plain item.
        Player player = playerWithSlots(wand, plain);
        var handler = harness.handler();
        WandSafetyListener listener = new WandSafetyListener(handler);

        handler.onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent());

        listener.onPlayerSwapHandItems(new PlayerSwapHandItemsEvent(player, wand, plain));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(), "swap must drop the range when the wand leaves the main hand");
        assertEquals(List.of(SelectionEndReason.ITEM_CHANGED), harness.endReasons);
    }

    @Test
    void swappingWandIntoTheMainHandGuidesReset() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        FakeItemStack plain = plainStack();
        // Off hand holds the wand; the swap brings it to the main hand.
        Player player = playerWithSlots(plain, wand);
        var handler = harness.handler();
        WandSafetyListener listener = new WandSafetyListener(handler);

        listener.onPlayerSwapHandItems(new PlayerSwapHandItemsEvent(player, plain, wand));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
        assertEquals(List.of(WandFeedback.Kind.RESET), harness.feedbackKinds());
    }

    @Test
    void selectionNotifierMapsOnlyCancelAndItemChange() {
        assertEquals("selection.wand.cancelled", WandSelectionNotifier.messageKey(SelectionEndReason.CANCELLED));
        assertEquals("selection.wand.abandoned", WandSelectionNotifier.messageKey(SelectionEndReason.ITEM_CHANGED));
        for (SelectionEndReason reason : SelectionEndReason.values()) {
            if (reason != SelectionEndReason.CANCELLED && reason != SelectionEndReason.ITEM_CHANGED) {
                assertNull(WandSelectionNotifier.messageKey(reason), "reason must stay silent: " + reason);
            }
        }
    }

    @Test
    void selectionNotifierSendsThroughInjectedSeamAndSkipsOffline() {
        List<String> sent = new ArrayList<>();
        Player player = playerWith(wandStack());
        WandSelectionNotifier notifier = new WandSelectionNotifier(uuid -> player, (p, key) -> sent.add(key));

        notifier.notify(new SelectionNotification(
                PLAYER_ID, SelectionEndReason.ITEM_CHANGED, Optional.empty(), Optional.empty(), false));
        notifier.notify(new SelectionNotification(
                PLAYER_ID, SelectionEndReason.TIMEOUT, Optional.empty(), Optional.empty(), false));
        assertEquals(List.of("selection.wand.abandoned"), sent, "timeout must not prompt");

        WandSelectionNotifier offline = new WandSelectionNotifier(uuid -> null, (p, key) -> sent.add(key));
        offline.notify(new SelectionNotification(
                PLAYER_ID, SelectionEndReason.CANCELLED, Optional.empty(), Optional.empty(), false));
        assertEquals(List.of("selection.wand.abandoned"), sent, "offline player stays silent");
    }

    @Test
    void blockBreakWithWandStaysSafetyOnlyAndNeverDuplicates() throws Exception {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();
        Block block = blockAt(world, 0, 64, 0);
        WandSafetyListener listener = harness.listener();

        // Creative path: interact LEFT alone opens the first corner.
        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block,
                BlockFace.NORTH, EquipmentSlot.HAND);
        listener.onPlayerInteract(left);
        assertTrue(left.isCancelled());
        long revision = harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectionRevision();

        // Survival path: the follow-up damage/break for the same hit only cancels.
        BlockDamageEvent damage = damageEvent(player, block, wand);
        listener.onBlockDamage(damage);
        assertTrue(damage.isCancelled());

        BlockBreakEvent breakEvent = breakEvent(block, player);
        listener.onBlockBreak(breakEvent);
        assertTrue(breakEvent.isCancelled());

        assertEquals(revision, harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectionRevision());
        assertEquals(1, harness.visualization.starts.size());
        assertEquals(1, harness.visualization.refreshes.size());
    }

    @Test
    void listenerLeftClickWithWandCancelsAndSelects() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();
        WandSafetyListener listener = harness.listener();

        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(left);
        assertTrue(left.isCancelled(), "wand left click must not damage the block");

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent(), "Creative left click must open a session without BlockDamage");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertTrue(session.get().pointB().isEmpty());
        assertEquals(1, harness.visualization.starts.size());
        assertEquals(1, harness.visualization.refreshes.size());
    }

    @Test
    void survivalInteractPlusDamageDoesNotDuplicate() throws Exception {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();
        Block block = blockAt(world, 0, 64, 0);
        WandSafetyListener listener = harness.listener();

        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, block,
                BlockFace.NORTH, EquipmentSlot.HAND);
        listener.onPlayerInteract(left);
        assertTrue(left.isCancelled());
        long revision = harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectionRevision();

        BlockDamageEvent damage = damageEvent(player, block, wand);
        listener.onBlockDamage(damage);
        assertTrue(damage.isCancelled(), "Survival follow-up damage must stay cancelled");

        assertEquals(revision, harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectionRevision(),
                "Interact+Damage for one left click must record a single corner");
        assertEquals(1, harness.visualization.refreshes.size());
    }

    @Test
    void damageAndBreakWithWandCancelButNeverSelect() throws Exception {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        Block block = blockAt(fakeWorld(), 0, 64, 0);
        WandSafetyListener listener = harness.listener();

        BlockDamageEvent damage = damageEvent(player, block, wand);
        listener.onBlockDamage(damage);
        assertTrue(damage.isCancelled());

        BlockBreakEvent breakEvent = breakEvent(block, player);
        listener.onBlockBreak(breakEvent);
        assertTrue(breakEvent.isCancelled());

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(),
                "damage/break alone must never open a session");
        assertTrue(harness.visualization.starts.isEmpty());
        assertTrue(harness.visualization.refreshes.isEmpty());
    }

    @Test
    void listenerRightClickWithWandCancelsAndSelects() throws Exception {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();
        WandSafetyListener listener = harness.listener();

        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(left);
        assertTrue(left.isCancelled(), "wand left click must not damage the block");

        PlayerInteractEvent right = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, blockAt(world, 16, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(right);
        assertTrue(right.isCancelled(), "wand right click must not interact with the block");

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 0)), session.get().pointB());
        assertEquals(2, session.get().selectedChunks().size());
        assertEquals(2, harness.visualization.refreshes.size(), "two corners must refresh twice, no duplicate");
    }

    @Test
    void listenerIgnoresNonWandOffhandAndOtherActions() {
        Harness harness = new Harness();
        FakeItemStack wand = wandStack();
        FakeItemStack plain = plainStack();
        Player wandPlayer = playerWith(wand);
        World world = fakeWorld();
        WandSafetyListener listener = harness.listener();

        PlayerInteractEvent plainRight = new PlayerInteractEvent(
                wandPlayer, Action.RIGHT_CLICK_BLOCK, plain, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(plainRight);
        assertFalse(plainRight.isCancelled(), "plain item must interact normally");

        PlayerInteractEvent plainLeft = new PlayerInteractEvent(
                wandPlayer, Action.LEFT_CLICK_BLOCK, plain, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(plainLeft);
        assertFalse(plainLeft.isCancelled(), "plain left item must interact normally");

        PlayerInteractEvent offhandRight = new PlayerInteractEvent(
                wandPlayer, Action.RIGHT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.OFF_HAND);
        listener.onPlayerInteract(offhandRight);
        assertFalse(offhandRight.isCancelled(), "offhand must never select");

        PlayerInteractEvent offhandLeft = new PlayerInteractEvent(
                wandPlayer, Action.LEFT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.OFF_HAND);
        listener.onPlayerInteract(offhandLeft);
        assertFalse(offhandLeft.isCancelled(), "offhand left must never select");

        PlayerInteractEvent leftAir = new PlayerInteractEvent(
                wandPlayer, Action.LEFT_CLICK_AIR, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(leftAir);
        assertFalse(leftAir.isCancelled(), "non-block action must be ignored");

        PlayerInteractEvent rightAir = new PlayerInteractEvent(
                wandPlayer, Action.RIGHT_CLICK_AIR, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(rightAir);
        assertFalse(rightAir.isCancelled(), "non-block action must be ignored");

        PlayerInteractEvent physical = new PlayerInteractEvent(
                wandPlayer, Action.PHYSICAL, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(physical);
        assertFalse(physical.isCancelled(), "physical action must be ignored");

        PlayerInteractEvent cancelledRight = new PlayerInteractEvent(
                wandPlayer, Action.RIGHT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        cancelledRight.setCancelled(true);
        listener.onPlayerInteract(cancelledRight);

        PlayerInteractEvent cancelledLeft = new PlayerInteractEvent(
                wandPlayer, Action.LEFT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        cancelledLeft.setCancelled(true);
        listener.onPlayerInteract(cancelledLeft);

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(), "ignored clicks must not open sessions");
        assertTrue(harness.visualization.starts.isEmpty());
        assertTrue(harness.visualization.refreshes.isEmpty());
    }

    @Test
    void invalidWorldLeavesNoSession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        Block noWorld = blockAt(null, 0, 64, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(player, noWorld, null, false));
        harness.handler().onWandRightClick(player, noWorld, null);
        harness.handler().onWandUse(new WandClickHandler.Context(null, noWorld, null, false));
        harness.handler().onWandRightClick(player, null, null);

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
        assertTrue(harness.visualization.starts.isEmpty());
        assertTrue(harness.visualization.refreshes.isEmpty());
    }

    @Test
    void rejectedRectangleKeepsSessionWithoutChunks() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();

        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), null, false));
        // Far past the 32-chunk side limit: the outcome is rejected, so nothing is stored.
        harness.handler().onWandRightClick(player, blockAt(world, 4096, 64, 0), null);

        Optional<SelectionSession> session = harness.manager.sessionFor(PLAYER_ID);
        assertTrue(session.isPresent(), "rejection must keep the first corner");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.get().pointA());
        assertTrue(session.get().pointB().isEmpty(), "rejected corner must not be stored");
        assertTrue(session.get().selectedChunks().isEmpty());
        assertEquals(
                List.of(WandFeedback.Kind.FIRST_POINT),
                harness.feedbackKinds(),
                "a rejected second corner must not advance the guidance");
        assertEquals(Map.of(), harness.feedback.get(harness.feedback.size() - 1).vars(),
                "a rejected click must not announce any size");
    }

    @Test
    void activeSubLandSessionReceivesWandPoint() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 7L, Set.of(
                new ChunkKey(WORLD_ID, 0, 0), new ChunkKey(WORLD_ID, 1, 0)));
        harness.manager.start(SelectionSession.initial(
                PLAYER_ID, WORLD_ID, SelectionMode.CREATE_SUBLAND,
                Optional.of(OWN_LAND), Optional.empty(),
                Optional.empty(), Optional.empty(), 7L, NOW));

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.pointA());
        assertTrue(session.pointB().isEmpty());

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 15, 64, 15), BlockFace.NORTH);
        SelectionSession completed = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 15, 64, 15)), completed.pointB());
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.SECOND_POINT),
                harness.feedbackKinds());
        assertEquals(Map.of("width", 1, "height", 1), harness.feedback.get(1).vars(),
                "SubLand feedback must carry real dimensions");

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 31, 64, 15), BlockFace.NORTH);
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.SECOND_POINT,
                        WandFeedback.Kind.RESIZED), harness.feedbackKinds());
        assertEquals(Map.of("width", 2, "height", 1), harness.feedback.get(2).vars(),
                "SubLand resize feedback must carry the resized dimensions");
    }

    @Test
    void activeSubLandClickOutsideParentIsIgnoredWithFeedback() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        harness.otherPlayerLand(OTHER_LAND, 1L, 1, 0);
        harness.manager.start(SelectionSession.initial(
                PLAYER_ID, WORLD_ID, SelectionMode.CREATE_SUBLAND,
                Optional.of(OWN_LAND), Optional.empty(),
                Optional.empty(), Optional.empty(), 3L, NOW));

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 16, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertTrue(session.pointA().isEmpty());
        assertEquals(List.of(WandFeedback.Kind.SUBLAND_OUTSIDE), harness.feedbackKinds());
    }

    @Test
    void firstPointOnOwnLandOpensEditTargetWithStructureRevision() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 7L, 0, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.EDIT_SELECTION, session.mode());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertEquals(7L, session.baseStructureRevision(),
                "the edit target must capture the land's current structure revision");
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)), session.pointA());
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET), harness.feedbackKinds());
        assertEquals(1, harness.visualization.starts.size());
    }

    @Test
    void firstPointOnAnotherPlayersLandIsBlockedWithoutASession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.otherPlayerLand(OTHER_LAND, 1L, 0, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(),
                "another player's land must never open a session");
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertTrue(harness.visualization.starts.isEmpty(), "a blocked first point must not start particles");
    }

    @Test
    void firstPointOnServerLandIsBlockedWithoutASession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.serverLand(SERVER_LAND, 1L, 0, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertTrue(harness.visualization.starts.isEmpty());
    }

    @Test
    void newClaimRectangleCrossingAnotherLandIsBlockedAndSessionUnchanged() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.otherPlayerLand(OTHER_LAND, 1L, 1, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        SelectionSession opened = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.CREATE_LAND, opened.mode());
        assertEquals(Optional.empty(), opened.targetLandId());
        long revision = opened.selectionRevision();
        long generation = opened.sessionGeneration();

        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        SelectionSession after = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertSame(opened, after, "a blocked candidate must not replace the session");
        assertEquals(revision, after.selectionRevision());
        assertEquals(generation, after.sessionGeneration());
        assertTrue(after.pointB().isEmpty(), "the blocked second corner must not be stored");
        assertTrue(after.selectedChunks().isEmpty());
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.BLOCKED), harness.feedbackKinds());

        // Repeating the blocked click must not re-prompt or move particles.
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.BLOCKED), harness.feedbackKinds(),
                "a repeated blocked click must stay silent");
        assertEquals(1, harness.visualization.starts.size());
        assertEquals(1, harness.visualization.refreshes.size());
    }

    @Test
    void newClaimRectangleCrossingOwnLandIsAlsoBlocked() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 4L, 1, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.CREATE_LAND, session.mode());
        assertTrue(session.selectedChunks().isEmpty(), "a new claim may not include the actor's own land");
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT, WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
    }

    @Test
    void ownLandEditRectangleWithTargetAndWildernessStaysEditSession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.EDIT_SELECTION, session.mode());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertEquals(3L, session.baseStructureRevision());
        assertEquals(2, session.selectedChunks().size(), "target chunk plus adjacent wilderness must be accepted");
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET, WandFeedback.Kind.SECOND_POINT), harness.feedbackKinds());
    }

    @Test
    void ownLandEditRectangleCrossingForeignLandIsBlocked() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        harness.otherPlayerLand(OTHER_LAND, 1L, 2, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 32, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.EDIT_SELECTION, session.mode());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertTrue(session.pointB().isEmpty(), "a foreign chunk inside the candidate must reject the corner");
        assertTrue(session.selectedChunks().isEmpty());
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET, WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
    }

    @Test
    void crossWorldClickNeverExtendsTheOldTarget() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld(WORLD_ID);
        World otherWorld = fakeWorld(OTHER_WORLD_ID);
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(Optional.of(OWN_LAND), harness.manager.sessionFor(PLAYER_ID).orElseThrow().targetLandId());

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(otherWorld, 0, 64, 0), BlockFace.NORTH, false));

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(OTHER_WORLD_ID, session.worldId(), "a click in another world starts a new session");
        assertEquals(SelectionMode.CREATE_LAND, session.mode(), "the old own-land target must not carry over");
        assertTrue(session.targetLandId().isEmpty());
    }

    @Test
    void subLandSessionReceivesWandClicksWithoutReenteringClaimFlow() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        SelectionSession subland = harness.manager.start(SelectionSession.initial(
                PLAYER_ID, WORLD_ID, SelectionMode.CREATE_SUBLAND, Optional.of(OWN_LAND),
                Optional.empty(), Optional.empty(), Optional.empty(), 3L, NOW));
        harness.feedback.clear();
        harness.visualization.starts.clear();
        harness.visualization.refreshes.clear();

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 1, 64, 1), BlockFace.NORTH, false));

        SelectionSession after = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.CREATE_SUBLAND, after.mode());
        assertEquals(Optional.of(OWN_LAND), after.targetLandId());
        assertEquals(Optional.of(new SelectionPoint(WORLD_ID, 1, 64, 1)), after.pointA());
        assertEquals(List.of(WandFeedback.Kind.FIRST_POINT), harness.feedbackKinds());
        assertTrue(harness.visualization.starts.isEmpty());
        assertEquals(1, harness.visualization.refreshes.size());
    }

    @Test
    void firstPointOnForeignLandPromptsOnceAndPreviewsItsActualBoundary() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        // An irregular foreign land: two non-adjacent chunks.
        ChunkKey a = new ChunkKey(WORLD_ID, 0, 0);
        otherLandChunks(harness, OTHER_LAND, 1L, Set.of(a, new ChunkKey(WORLD_ID, 2, 1)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size());
        assertEquals(Set.of(a, new ChunkKey(WORLD_ID, 2, 1)), harness.preview.shown.get(0).chunks(),
                "the preview must outline the land's real chunk set");
        assertEquals(65.0, harness.preview.shown.get(0).planeY());

        // A repeated blocked first click stays silent and does not restart the preview.
        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size());
    }

    @Test
    void lifecycleClearForgetsDedupSoTheBlockedLandPromptsAndPreviewsAgain() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        otherLandChunks(harness, OTHER_LAND, 1L, Set.of(new ChunkKey(WORLD_ID, 0, 0)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size());

        // A lifecycle teardown (quit / world change) drops the session and its
        // preview, so it must also forget the per-player prompt/preview dedup.
        handler.onSelectionCleared(PLAYER_ID);

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED, WandFeedback.Kind.BLOCKED), harness.feedbackKinds(),
                "after a teardown the same blocked land must prompt again");
        assertEquals(2, harness.preview.shown.size(),
                "after a teardown the same blocked land must preview again");
    }

    @Test
    void quitThenReclickingTheSameBlockedLandPromptsAndPreviewsAgain() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        otherLandChunks(harness, OTHER_LAND, 1L, Set.of(new ChunkKey(WORLD_ID, 0, 0)));
        SelectionWandClickHandler handler = harness.handler();
        SelectionLifecycleListener lifecycle = new SelectionLifecycleListener(
                harness.manager, harness.preview, handler::onSelectionCleared);

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size());

        lifecycle.onPlayerQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(harness.preview.stopped.contains(PLAYER_ID), "quit must stop the orphan preview");

        // Rejoining and hitting the same blocked land must prompt and preview again.
        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED, WandFeedback.Kind.BLOCKED), harness.feedbackKinds(),
                "a rejoining player must be told again that the land is blocked");
        assertEquals(2, harness.preview.shown.size(),
                "a rejoining player must see the blocking boundary again");
    }

    @Test
    void worldChangeThenReclickingTheSameBlockedLandPromptsAndPreviewsAgain() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        otherLandChunks(harness, OTHER_LAND, 1L, Set.of(new ChunkKey(WORLD_ID, 0, 0)));
        SelectionWandClickHandler handler = harness.handler();
        SelectionLifecycleListener lifecycle = new SelectionLifecycleListener(
                harness.manager, harness.preview, handler::onSelectionCleared);

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(1, harness.preview.shown.size());

        lifecycle.onPlayerChangedWorld(new PlayerChangedWorldEvent(player, fakeWorld(OTHER_WORLD_ID)));

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.BLOCKED, WandFeedback.Kind.BLOCKED), harness.feedbackKinds(),
                "after a world change the same blocked land must prompt again");
        assertEquals(2, harness.preview.shown.size(),
                "after a world change the same blocked land must preview again");
    }

    @Test
    void firstPointOnOwnLandOpensEditTargetAndPreviewsBaseline() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 3L, 0, 0);

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.EDIT_SELECTION, session.mode());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size(), "the existing range is shown as the baseline");
        assertEquals(Set.of(new ChunkKey(WORLD_ID, 0, 0)), harness.preview.shown.get(0).chunks());
    }

    @Test
    void acceptedOwnEditSelectionStopsTheBaselinePreview() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        harness.preview.stopped.clear();

        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        assertEquals(List.of(PLAYER_ID), harness.preview.stopped,
                "an accepted selection replaces the preview with the normal renderer");
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET, WandFeedback.Kind.SECOND_POINT), harness.feedbackKinds());
    }

    @Test
    void blockedCandidatePreviewsTheForeignBoundaryAndKeepsTheSession() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        otherLandChunks(harness, OTHER_LAND, 1L, Set.of(new ChunkKey(WORLD_ID, 2, 0)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        SelectionSession opened = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        harness.preview.shown.clear();

        handler.onWandRightClick(player, blockAt(world, 32, 64, 0), BlockFace.NORTH);

        assertSame(opened, harness.manager.sessionFor(PLAYER_ID).orElseThrow(), "the session must not change");
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET, WandFeedback.Kind.BLOCKED), harness.feedbackKinds());
        assertEquals(1, harness.preview.shown.size(), "the blocking land's boundary is previewed");
        assertEquals(Set.of(new ChunkKey(WORLD_ID, 2, 0)), harness.preview.shown.get(0).chunks());
    }

    @Test
    void wandUnequipStopsTheOccupiedPreview() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.ownLand(OWN_LAND, 3L, 0, 0);
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(1, harness.preview.shown.size());
        harness.preview.stopped.clear();

        handler.onWandUnequipped(player);

        assertEquals(List.of(PLAYER_ID), harness.preview.stopped);
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
    }

    @Test
    void unhydratedRegistryFailsClosedWithUnavailablePrompt() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.registryReady = false;
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(),
                "an unhydrated registry must not open a session");
        assertEquals(List.of(WandFeedback.Kind.UNAVAILABLE), harness.feedbackKinds());
        assertTrue(harness.preview.shown.isEmpty());

        // Repeating the click stays silent; once hydrated, occupancy is judged again.
        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertEquals(List.of(WandFeedback.Kind.UNAVAILABLE), harness.feedbackKinds());
    }

    @Test
    void hydratedRegistryIsRequiredBeforeWildernessIsAccepted() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.registryReady = false;
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());

        harness.registryReady = true;
        handler.onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent(),
                "after hydration a wilderness click opens a claim session");
    }

    private static void otherLandChunks(Harness harness, LandId landId, long revision, Set<ChunkKey> chunks) {
        harness.revisions.put(landId, revision);
        harness.boundaries.put(landId, Set.copyOf(chunks));
        for (ChunkKey chunk : chunks) {
            harness.lands.put(WorldChunkIndex.pack(chunk.chunkX(), chunk.chunkZ()),
                    new SelectionLandContext(landId, OwnerRef.player(UUID.randomUUID()), revision));
        }
    }

    @Test
    void quitWorldChangeAndDisableClearWandSessions() {
        Harness harness = new Harness();
        Player player = playerWith(wandStack());
        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent());

        harness.manager.onWorldChange(PLAYER_ID);
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());

        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        harness.manager.onPlayerQuit(PLAYER_ID);
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());

        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        harness.manager.disable();
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());

        // Disabled registry stays fail-closed instead of reopening sessions.
        harness.handler().onWandUse(
                new WandClickHandler.Context(player, blockAt(fakeWorld(), 0, 64, 0), null, false));
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty());
    }

    @Test
    void productionWiringUsesSelectingHandler() throws Exception {
        Harness harness = new Harness();
        WandSafetyListener listener = ChunkLandPlugin.buildWandSafetyListener(
                harness.manager, () -> harness.edits, harness.clock);
        assertNotNull(listener);

        FakeItemStack wand = wandStack();
        Player player = playerWith(wand);
        World world = fakeWorld();
        PlayerInteractEvent left = new PlayerInteractEvent(
                player, Action.LEFT_CLICK_BLOCK, wand, blockAt(world, 0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(left);
        assertTrue(left.isCancelled());
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isPresent(),
                "production wiring must not use the no-op handler");

        PlayerInteractEvent right = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, wand, blockAt(world, 16, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        listener.onPlayerInteract(right);
        assertTrue(right.isCancelled());
        assertEquals(2, harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectedChunks().size());
    }

    @Test
    void wandClickPathNeverLoadsChunksOrTouchesNetworkOrStorage() throws Exception {
        List<Path> sources = List.of(
                locateProduction("wand", "SelectionWandClickHandler.java"),
                locateProduction("wand", "WandSafetyListener.java"));
        List<String> forbidden = List.of(
                "getChunk",
                "loadChunk",
                "getSnapshot",
                "getBlockData",
                "getHighestBlock",
                "getState(",
                "java.sql",
                "java.net.http",
                "HttpClient",
                "Bukkit.get",
                "getServer(");
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            for (String token : forbidden) {
                if (content.contains(token)) {
                    violations.add(source.getFileName() + " contains forbidden '" + token + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), "wand click path violations: " + violations);

        // The ownership guard and the occupied preview run on the same click
        // path, so they obey the same hot-path contract with one stricter
        // rule: unlike the handler (which reads block coordinates), they must
        // not reference Bukkit at all — occupancy comes only from the
        // immutable registry snapshot through the lookup seams.
        List<Path> guardSources = List.of(
                locateProduction("selection", "SelectionWandGuard.java"),
                locateProduction("selection", "SelectionLandLookup.java"),
                locateProduction("selection", "SelectionLandContext.java"),
                locateProduction("selection", "SelectionLandBoundaryLookup.java"),
                locateProduction("selection", "OccupiedPreviewController.java"),
                locateProduction("selection", "OccupiedPreviewRenderer.java"));
        List<String> guardForbidden = new ArrayList<>(forbidden);
        guardForbidden.add("org.bukkit");
        for (Path source : guardSources) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            for (String token : guardForbidden) {
                if (content.contains(token)) {
                    violations.add(source.getFileName() + " contains forbidden '" + token + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), "selection guard hot-path violations: " + violations);
        String guard = Files.readString(
                locateProduction("selection", "SelectionWandGuard.java"), StandardCharsets.UTF_8);
        assertTrue(guard.contains("SelectionLandLookup"),
                "the guard must read occupancy only through the snapshot lookup seam");
        String lookupContract = Files.readString(
                locateProduction("selection", "SelectionLandLookup.java"), StandardCharsets.UTF_8);
        assertTrue(lookupContract.contains("snapshot"),
                "the lookup contract must pin the immutable snapshot source");
    }

    private static Path locateProduction(String dir, String file) {
        for (String candidate : List.of(
                "src/main/java/com/smile/chunkland/" + dir + "/" + file,
                "chunkland-plugin/src/main/java/com/smile/chunkland/" + dir + "/" + file)) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        fail("cannot locate production source " + file + " from working dir " + Path.of("").toAbsolutePath());
        throw new AssertionError("unreachable");
    }
}
