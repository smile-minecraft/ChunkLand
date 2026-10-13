package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionPreviewColor;
import com.smile.chunkland.selection.SelectionPreviewColorResolver;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
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
import org.junit.jupiter.api.Test;

/**
 * Chunk-edit geometry on top of the wand flow: opening on owned land loads
 * the land's full chunks as the baseline, and while the geometry flag is
 * set the left button adds one chunk and the right button removes one chunk
 * through the same edit service the rectangle path uses.
 */
class SelectionWandChunkClickTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LandId OWN_LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final LandId OTHER_LAND = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private static final class Harness {
        final Map<Long, SelectionLandContext> lands = new LinkedHashMap<>();
        final Map<LandId, Long> revisions = new HashMap<>();
        final Map<LandId, Set<ChunkKey>> boundaries = new LinkedHashMap<>();
        final List<WandFeedback.Kind> feedback = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();
        final List<SelectionEndReason> endReasons = new ArrayList<>();
        final SelectionClock clock = () -> NOW;
        final SelectionLandLookup lookup = (world, chunkX, chunkZ) -> {
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
            Set<ChunkKey> chunks = boundaries.get(landId);
            return chunks == null ? Optional.empty() : Optional.of(chunks);
        };
        final SelectionStructureRevisionLookup revisionsLookup = landId -> {
            Long value = revisions.get(landId);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        };
        final SelectionSessionManager manager = new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                new SelectionVisualizationTaskController() {
                    @Override
                    public void stop(UUID playerId) {
                    }

                    @Override
                    public void start(SelectionSession session) {
                    }

                    @Override
                    public void refresh(SelectionSession session) {
                    }
                },
                notification -> endReasons.add(notification.reason()),
                (SelectionClock) () -> NOW,
                () -> Duration.ofMinutes(10),
                uuid -> Optional.empty(),
                revisionsLookup);
        final com.smile.chunkland.selection.SelectionEditService edits =
                new com.smile.chunkland.selection.SelectionEditService(
                        new LimitSettings(5, 256, 128, 16, 32, 1024),
                        index);

        void ownLand(LandId landId, long revision, Set<ChunkKey> chunks) {
            revisions.put(landId, revision);
            boundaries.put(landId, Set.copyOf(chunks));
            for (ChunkKey chunk : chunks) {
                lands.put(WorldChunkIndex.pack(chunk.chunkX(), chunk.chunkZ()),
                        new SelectionLandContext(landId, OwnerRef.player(PLAYER_ID), revision));
            }
        }

        void otherLand(LandId landId, long revision, Set<ChunkKey> chunks) {
            revisions.put(landId, revision);
            boundaries.put(landId, Set.copyOf(chunks));
            for (ChunkKey chunk : chunks) {
                lands.put(WorldChunkIndex.pack(chunk.chunkX(), chunk.chunkZ()),
                        new SelectionLandContext(landId, OwnerRef.player(UUID.randomUUID()), revision));
            }
        }

        SelectionWandClickHandler handler() {
            return new SelectionWandClickHandler(
                    manager, () -> edits, clock,
                    (player, kind, stepVars) -> {
                        feedback.add(kind);
                        vars.add(Map.copyOf(stepVars));
                    },
                    lookup, boundaryLookup, OccupiedPreviewController.noop(),
                    SelectionPreviewColorResolver.blocked());
        }
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId" -> { return PLAYER_ID; }
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
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                        }
                    }
                });
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void openingOnOwnLandLoadsFullBaseline() {
        Harness harness = new Harness();
        Player player = player();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(0, 0), chunk(1, 0)));

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.EDIT_SELECTION, session.mode());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)), session.selectedChunks(),
                "editing an existing land must start from its full current chunks");
        assertTrue(session.pendingChanges().isEmpty(), "the baseline is durable state, not a change");
        assertEquals(List.of(WandFeedback.Kind.EDIT_TARGET), harness.feedback);
    }

    @Test
    void chunkModeLeftAddsAndRightRemovesOnNewClaim() {
        Harness harness = new Harness();
        Player player = player();
        World world = fakeWorld();
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 15, 64, 15), BlockFace.NORTH);
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 16, 64, 0), BlockFace.NORTH, false));

        SelectionSession added = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)), added.selectedChunks(),
                "left click in chunk mode must add the pointed chunk");
        assertEquals(WandFeedback.Kind.CHUNK_ADDED, harness.feedback.get(harness.feedback.size() - 1));
        assertEquals(Map.of("chunk_count", 2), harness.vars.get(harness.vars.size() - 1),
                "chunk feedback must carry the actual chunk count, not the bounding size");

        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        SelectionSession removed = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(0, 0)), removed.selectedChunks(),
                "right click in chunk mode must remove the pointed chunk");
        assertEquals(WandFeedback.Kind.CHUNK_REMOVED, harness.feedback.get(harness.feedback.size() - 1));
        assertEquals(Map.of("chunk_count", 1), harness.vars.get(harness.vars.size() - 1));
    }

    @Test
    void chunkModeRemoveBaselineChunkKeepsRevisionGuard() {
        Harness harness = new Harness();
        Player player = player();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(0, 0), chunk(1, 0)));

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(1, 0)), session.selectedChunks());
        assertEquals(Map.of(chunk(0, 0), com.smile.chunkland.selection.PendingChange.REMOVE),
                session.pendingChanges());
        assertEquals(Optional.of(OWN_LAND), session.targetLandId());
        assertEquals(7L, session.baseStructureRevision());
    }

    @Test
    void chunkModeRemoveBridgeIsRejectedAndExplained() {
        Harness harness = new Harness();
        Player player = player();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0)));

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));
        SelectionSession before = harness.manager.sessionFor(PLAYER_ID).orElseThrow();

        harness.handler().onWandRightClick(player, blockAt(fakeWorld(), 16, 64, 0), BlockFace.NORTH);

        SelectionSession after = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertSame(before, after, "a rejected bridge removal must not replace the session");
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0)), after.selectedChunks());
        assertEquals(WandFeedback.Kind.CHUNK_SPLIT, harness.feedback.get(harness.feedback.size() - 1),
                "the player must be told why the bridge chunk stays");
    }

    @Test
    void chunkModeAddOccupiedChunkIsBlocked() {
        Harness harness = new Harness();
        Player player = player();
        World world = fakeWorld();
        harness.otherLand(OTHER_LAND, 1L, Set.of(chunk(2, 0)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 15, 64, 15), BlockFace.NORTH);
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));
        SelectionSession before = harness.manager.sessionFor(PLAYER_ID).orElseThrow();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 32, 64, 0), BlockFace.NORTH, false));

        SelectionSession after = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertSame(before, after, "a blocked chunk add must not replace the session");
        assertEquals(Set.of(chunk(0, 0)), after.selectedChunks());
        assertEquals(WandFeedback.Kind.BLOCKED, harness.feedback.get(harness.feedback.size() - 1));
    }

    @Test
    void duplicateChunkAddStaysSilent() {
        Harness harness = new Harness();
        Player player = player();
        World world = fakeWorld();
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 15, 64, 15), BlockFace.NORTH);
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));
        int prompts = harness.feedback.size();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));

        assertEquals(prompts, harness.feedback.size(), "adding the same chunk twice must stay silent");
        assertEquals(Set.of(chunk(0, 0)), harness.manager.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
    }

    @Test
    void togglingChunkModeNeverLoadsExistingLandIntoNewClaim() {
        Harness harness = new Harness();
        Player player = player();
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(5, 5)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        handler.onWandRightClick(player, blockAt(world, 15, 64, 15), BlockFace.NORTH);
        SelectionSession before = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(SelectionMode.CREATE_LAND, before.mode());

        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));
        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, false));

        SelectionSession after = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertSame(before, after, "toggling the geometry flag must not replace the session");
        assertEquals(Set.of(chunk(0, 0)), after.selectedChunks(),
                "a fresh claim must never gain existing land chunks by toggling");
        assertTrue(after.targetLandId().isEmpty());
    }

    @Test
    void rectangleClicksReplaceTheBaselineWhileChunkFlagIsOff() {
        Harness harness = new Harness();
        Player player = player();
        World world = fakeWorld();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(0, 0), chunk(1, 0), chunk(2, 0)));
        var handler = harness.handler();

        handler.onWandUse(new WandClickHandler.Context(player, blockAt(world, 0, 64, 0), BlockFace.NORTH, false));
        assertFalse(harness.manager.isChunkEdit(PLAYER_ID));
        handler.onWandRightClick(player, blockAt(world, 16, 64, 0), BlockFace.NORTH);

        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)), session.selectedChunks(),
                "rectangle clicks keep their legacy replace semantics");
        assertEquals(WandFeedback.Kind.SECOND_POINT, harness.feedback.get(harness.feedback.size() - 1));
    }

    @Test
    void chunkFlagDiesWithTheSession() {
        Harness harness = new Harness();
        Player player = player();
        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));

        assertTrue(harness.manager.setChunkEdit(PLAYER_ID, true));
        assertTrue(harness.manager.isChunkEdit(PLAYER_ID));
        assertTrue(harness.manager.clear(PLAYER_ID,
                com.smile.chunkland.selection.SelectionEndReason.CANCELLED));

        assertFalse(harness.manager.isChunkEdit(PLAYER_ID));
        assertFalse(harness.manager.setChunkEdit(PLAYER_ID, true),
                "a toggle without a live session must fail closed");
    }

    @Test
    void staleStructureRevisionInvalidatesTheSession() {
        Harness harness = new Harness();
        Player player = player();
        harness.ownLand(OWN_LAND, 7L, Set.of(chunk(0, 0)));

        harness.handler().onWandUse(new WandClickHandler.Context(
                player, blockAt(fakeWorld(), 0, 64, 0), BlockFace.NORTH, false));
        SelectionSession session = harness.manager.sessionFor(PLAYER_ID).orElseThrow();
        harness.revisions.put(OWN_LAND, 8L);

        var rejected = harness.manager.updateSelection(PLAYER_ID, session, 8L,
                new com.smile.chunkland.selection.SelectionUpdate(
                        session.pointA(), session.pointB(), session.selectedChunks(),
                        session.pendingChanges()));

        assertTrue(rejected.isEmpty(), "an edit against a stale structure revision must be refused");
        assertTrue(harness.manager.sessionFor(PLAYER_ID).isEmpty(),
                "the stale session must be invalidated, never kept");
    }
}
