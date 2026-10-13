package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.runtime.index.WorldChunkIndex;
import com.smile.chunkland.selection.PendingChange;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionEditService;
import com.smile.chunkland.selection.SelectionLandContext;
import com.smile.chunkland.selection.SelectionLandIndex;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Chat entry points for chunk-edit geometry: mode switches and single-chunk
 * adds/removes run the same edit service as the wand clicks, so both ends
 * reach the same selections without depending on left/right buttons.
 */
class SelectionCommandHandlerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LandId OWN_LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final LandId OTHER_LAND = new LandId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private static final class Harness {
        final Map<Long, LandId> lands = new LinkedHashMap<>();
        final Map<LandId, Long> revisions = new java.util.HashMap<>();
        final SelectionLandIndex index = (world, packed) -> lands.get(packed);
        final SelectionSessionManager selections = new SelectionSessionManager(
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
                notification -> {
                },
                (SelectionClock) () -> NOW,
                () -> Duration.ofMinutes(10),
                uuid -> Optional.empty(),
                landId -> {
                    Long value = revisions.get(landId);
                    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
                });
        final SelectionEditService edits = new SelectionEditService(
                new LimitSettings(5, 256, 128, 16, 32, 1024), index);
        final Map<UUID, ChunkKey> standing = new ConcurrentHashMap<>();
        final List<String> keys = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();
        final ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> variables) {
                keys.add(key);
                vars.add(Map.copyOf(variables));
            }

            @Override
            public void reply(String key, Map<String, Object> variables, java.util.Locale locale) {
                reply(key, variables);
            }
        };

        SelectionCommandHandler handler() {
            return new SelectionCommandHandler(selections, () -> edits,
                    player -> Optional.ofNullable(standing.get(player.getUniqueId())));
        }

        /** Fresh claim session holding exactly the given chunks at revision 1. */
        SelectionSession claimSession(Set<ChunkKey> chunks) {
            SelectionSession initial = SelectionSession.initial(
                    PLAYER_ID, WORLD_ID, SelectionMode.CREATE_LAND, Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)),
                    Optional.of(new SelectionPoint(WORLD_ID, 15, 64, 15)), 0L, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(PLAYER_ID, stamped, new SelectionUpdate(
                            stamped.pointA(), stamped.pointB(), chunks, Map.of()))
                    .orElseThrow(() -> new IllegalStateException("setup update must succeed"));
        }

        SelectionSession editSession(Set<ChunkKey> chunks) {
            revisions.put(OWN_LAND, 3L);
            SelectionSession initial = SelectionSession.initial(
                    PLAYER_ID, WORLD_ID, SelectionMode.EDIT_SELECTION, Optional.of(OWN_LAND),
                    Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)),
                    Optional.empty(), 3L, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(PLAYER_ID, stamped, 3L, new SelectionUpdate(
                            stamped.pointA(), stamped.pointB(), chunks, Map.of()))
                    .orElseThrow(() -> new IllegalStateException("setup update must succeed"));
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

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getServer")) {
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void chunkVerbArmsChunkEditsWithoutTouchingSelection() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));

        harness.handler().handle(player(), new String[]{"selection", "chunk"}, harness.sink);

        assertTrue(harness.selections.isChunkEdit(PLAYER_ID));
        assertEquals(List.of("command.land.selection.mode_chunk"), harness.keys);
        assertEquals(Map.of("chunk_count", 1), harness.vars.get(0));
        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
    }

    @Test
    void rectangleVerbDisarmsChunkEdits() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.selections.setChunkEdit(PLAYER_ID, true);

        harness.handler().handle(player(), new String[]{"selection", "rectangle"}, harness.sink);

        assertFalse(harness.selections.isChunkEdit(PLAYER_ID));
        assertEquals(List.of("command.land.selection.mode_rectangle"), harness.keys);
    }

    @Test
    void addStandingChunkMatchesTheWandAddPath() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.standing.put(PLAYER_ID, chunk(1, 0));

        harness.handler().handle(player(), new String[]{"selection", "add"}, harness.sink);

        SelectionSession after = harness.selections.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(0, 0), chunk(1, 0)), after.selectedChunks());
        assertEquals(List.of("command.land.selection.added"), harness.keys);
        assertEquals(Map.of("chunk_count", 2), harness.vars.get(0));

        SelectionEditService direct = harness.edits;
        SelectionSession before = harness.selections.sessionFor(PLAYER_ID).orElseThrow();
        var duplicate = direct.addChunk(before, chunk(1, 0));
        assertFalse(duplicate.accepted(), "the same service must judge the same chunk the same way");
    }

    @Test
    void addExplicitBlockCoordinatesUsesFloorDivision() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(-1, -1)));

        harness.handler().handle(player(), new String[]{"selection", "add", "-1", "-17"}, harness.sink);

        assertEquals(Set.of(chunk(-1, -1), chunk(-1, -2)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks(),
                "block (-1,-17) sits in chunk (-1,-2) and must connect to the existing chunk");
    }

    @Test
    void removeStandingChunkOnOwnedLandRecordsPendingRemove() {
        Harness harness = new Harness();
        harness.editSession(Set.of(chunk(0, 0), chunk(1, 0)));
        harness.standing.put(PLAYER_ID, chunk(0, 0));

        harness.handler().handle(player(), new String[]{"selection", "remove"}, harness.sink);

        SelectionSession after = harness.selections.sessionFor(PLAYER_ID).orElseThrow();
        assertEquals(Set.of(chunk(1, 0)), after.selectedChunks());
        assertEquals(Map.of(chunk(0, 0), PendingChange.REMOVE), after.pendingChanges());
        assertEquals(List.of("command.land.selection.removed"), harness.keys);
        assertEquals(Map.of("chunk_count", 1), harness.vars.get(0));
    }

    @Test
    void addOccupiedChunkIsRejectedWithReason() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.lands.put(chunk(1, 0).pack(), OTHER_LAND);
        harness.standing.put(PLAYER_ID, chunk(1, 0));

        harness.handler().handle(player(), new String[]{"selection", "add"}, harness.sink);

        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
        assertEquals(List.of("command.land.selection.rejected"), harness.keys);
    }

    @Test
    void duplicateAddReportsNoChange() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0)));
        harness.standing.put(PLAYER_ID, chunk(0, 0));

        harness.handler().handle(player(), new String[]{"selection", "add"}, harness.sink);

        assertEquals(List.of("command.land.selection.no_change"), harness.keys);
        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
    }

    @Test
    void infoReportsActualCountAndGeometry() {
        Harness harness = new Harness();
        harness.claimSession(Set.of(chunk(0, 0), chunk(1, 0)));
        harness.selections.setChunkEdit(PLAYER_ID, true);

        harness.handler().handle(player(), new String[]{"selection", "info"}, harness.sink);

        assertEquals(List.of("command.land.selection.info"), harness.keys);
        assertEquals(2, harness.vars.get(0).get("chunk_count"));
    }

    @Test
    void missingSessionConsoleUnknownVerbAndBadCoordinatesFailClosed() {
        Harness harness = new Harness();

        harness.handler().handle(player(), new String[]{"selection", "chunk"}, harness.sink);
        assertEquals(List.of("command.land.selection.no_selection"), harness.keys);

        harness.handler().handle(console(), new String[]{"selection", "info"}, harness.sink);
        assertEquals("command.land.selection.console", harness.keys.get(harness.keys.size() - 1));

        harness.claimSession(Set.of(chunk(0, 0)));
        harness.handler().handle(player(), new String[]{"selection", "frobnicate"}, harness.sink);
        assertEquals("command.land.selection.usage", harness.keys.get(harness.keys.size() - 1));

        harness.handler().handle(player(), new String[]{"selection", "add", "east", "0"}, harness.sink);
        assertEquals("command.land.selection.usage", harness.keys.get(harness.keys.size() - 1));
        assertEquals(Set.of(chunk(0, 0)),
                harness.selections.sessionFor(PLAYER_ID).orElseThrow().selectedChunks());
    }

    @Test
    void subLandSessionsRejectChunkEdits() {
        Harness harness = new Harness();
        SelectionSession initial = SelectionSession.initial(
                PLAYER_ID, WORLD_ID, SelectionMode.CREATE_SUBLAND, Optional.of(OWN_LAND),
                Optional.empty(), Optional.empty(), Optional.empty(), 3L, NOW);
        harness.selections.start(initial);
        harness.standing.put(PLAYER_ID, chunk(0, 0));

        harness.handler().handle(player(), new String[]{"selection", "add"}, harness.sink);

        assertEquals(List.of("command.land.selection.wrong_mode"), harness.keys);
    }
}
