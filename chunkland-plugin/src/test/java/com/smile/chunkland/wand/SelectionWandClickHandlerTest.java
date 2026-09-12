package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.selection.SelectionLandIndex;
import com.smile.chunkland.selection.SelectionNotification;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
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
        final RecordingVisualization visualization = new RecordingVisualization();
        final List<Step> feedback = new ArrayList<>();
        final List<SelectionEndReason> endReasons = new ArrayList<>();
        final SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                visualization,
                notification -> endReasons.add(notification.reason()),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10));
        final com.smile.chunkland.selection.SelectionEditService edits =
                new com.smile.chunkland.selection.SelectionEditService(
                        new LimitSettings(5, 256, 128, 16, 32, 1024),
                        (SelectionLandIndex) (world, packed) -> null);
        final SelectionClock clock = () -> NOW;

        /** One recorded guidance prompt: the step plus the vars handed to the message pipeline. */
        record Step(WandFeedback.Kind kind, Map<String, Object> vars) {}

        SelectionWandClickHandler handler() {
            return new SelectionWandClickHandler(
                    manager, () -> edits, clock, (player, kind, vars) -> feedback.add(new Step(kind, vars)));
        }

        List<WandFeedback.Kind> feedbackKinds() {
            return feedback.stream().map(Step::kind).toList();
        }

        WandSafetyListener listener() {
            return new WandSafetyListener(handler());
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
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID" -> { return WORLD_ID; }
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
