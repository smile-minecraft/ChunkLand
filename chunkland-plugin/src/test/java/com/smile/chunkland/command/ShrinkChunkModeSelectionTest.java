package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.ShrinkOutcome;
import com.smile.chunkland.claim.ShrinkRequest;
import com.smile.chunkland.selection.SelectionClock;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Shrink reads chunk-edit selections as the desired remaining shape: the
 * removal is the owned chunks minus the selection. Rectangle selections keep
 * their legacy meaning (the selection itself is the removal).
 */
class ShrinkChunkModeSelectionTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LandId LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private static final class Harness {
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
                landId -> LAND.equals(landId) ? OptionalLong.of(3L) : OptionalLong.empty());
        final AtomicReference<ShrinkRequest> requested = new AtomicReference<>();
        final List<String> keys = new ArrayList<>();
        final ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> variables) {
                keys.add(key);
            }

            @Override
            public void reply(String key, Map<String, Object> variables, Locale locale) {
                reply(key, variables);
            }
        };

        ShrinkCommandHandler handler() {
            ShrinkCommandHandler.ShrinkRunner runner = request -> {
                requested.set(request);
                return CompletableFuture.completedStage(ShrinkOutcome.success(LAND, 0L));
            };
            return new ShrinkCommandHandler(selections, runner, null,
                    sender -> Optional.of(LAND),
                    landId -> Optional.of(OwnerRef.player(PLAYER_ID)),
                    landId -> Optional.of(Set.of(chunk(0, 0), chunk(1, 0))));
        }

        /** Edit session whose selection is the desired remaining shape. */
        void desiredShape(Set<ChunkKey> remaining) {
            SelectionSession initial = SelectionSession.initial(
                    PLAYER_ID, WORLD_ID, SelectionMode.EDIT_SELECTION, Optional.of(LAND),
                    Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)),
                    Optional.empty(), 3L, NOW);
            SelectionSession stamped = selections.start(initial);
            selections.updateSelection(PLAYER_ID, stamped, 3L, new SelectionUpdate(
                            stamped.pointA(), stamped.pointB(), remaining, Map.of()))
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

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void chunkModeShrinkRemovesOwnedMinusSelection() {
        Harness harness = new Harness();
        harness.desiredShape(Set.of(chunk(1, 0)));
        assertTrue(harness.selections.setChunkEdit(PLAYER_ID, true));

        harness.handler().handle(player(), new String[]{"shrink"}, harness.sink);

        ShrinkRequest request = harness.requested.get();
        assertTrue(request != null, "chunk-mode shrink must reach the saga");
        assertEquals(Set.of(chunk(0, 0)), request.delta(),
                "the removal is the owned chunks minus the desired shape, never the shape itself");
    }

    @Test
    void chunkModeWithNothingRemovedReportsNoSelection() {
        Harness harness = new Harness();
        harness.desiredShape(Set.of(chunk(0, 0), chunk(1, 0)));
        assertTrue(harness.selections.setChunkEdit(PLAYER_ID, true));

        harness.handler().handle(player(), new String[]{"shrink"}, harness.sink);

        assertTrue(harness.requested.get() == null, "an empty removal must never reach the saga");
        assertEquals(List.of("command.land.shrink.no_selection"), harness.keys);
    }

    @Test
    void rectangleModeKeepsLegacyRemovalSemantics() {
        Harness harness = new Harness();
        harness.desiredShape(Set.of(chunk(1, 0)));

        harness.handler().handle(player(), new String[]{"shrink"}, harness.sink);

        ShrinkRequest request = harness.requested.get();
        assertTrue(request != null, "rectangle shrink must keep working");
        assertEquals(Set.of(chunk(1, 0)), request.delta(),
                "without chunk edits the selection itself stays the removal");
    }
}
