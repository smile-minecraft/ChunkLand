package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRequest;
import com.smile.chunkland.claim.ExpandRequest;
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
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * An irregular (non-rectangular) selection completes claims and expansions
 * with its actual chunk set: the saga request, the preview and the reported
 * count all follow the set, never the bounding rectangle.
 */
class IrregularSelectionClaimExpandTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LandId OWN_LAND = new LandId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** Connected L-shape: three chunks inside a 2x2 bounding box. */
    private static Set<ChunkKey> lShape() {
        return Set.of(chunk(0, 0), chunk(1, 0), chunk(0, 1));
    }

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
                landId -> OWN_LAND.equals(landId) ? OptionalLong.of(3L) : OptionalLong.empty());
        final List<String> keys = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();
        final ReplySink sink = new ReplySink() {
            @Override
            public void reply(String key, Map<String, Object> variables) {
                keys.add(key);
                vars.add(Map.copyOf(variables));
            }

            @Override
            public void reply(String key, Map<String, Object> variables, Locale locale) {
                reply(key, variables);
            }
        };

        SelectionSession claimSession(Set<ChunkKey> chunks) {
            SelectionSession initial = SelectionSession.initial(
                    PLAYER_ID, WORLD_ID, SelectionMode.CREATE_LAND, Optional.empty(), Optional.empty(),
                    Optional.of(new SelectionPoint(WORLD_ID, 0, 64, 0)),
                    Optional.of(new SelectionPoint(WORLD_ID, 16, 64, 16)), 0L, NOW);
            SelectionSession stamped = selections.start(initial);
            return selections.updateSelection(PLAYER_ID, stamped, new SelectionUpdate(
                            stamped.pointA(), stamped.pointB(), chunks, Map.of()))
                    .orElseThrow(() -> new IllegalStateException("setup update must succeed"));
        }

        SelectionSession editSession(Set<ChunkKey> chunks) {
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

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD_ID, x, z);
    }

    @Test
    void irregularClaimCarriesActualChunksWithActualCount() {
        Harness harness = new Harness();
        SelectionSession session = harness.claimSession(lShape());
        assertEquals(3, ClaimPreview.fromSession(session, "Home").chunkCount(),
                "the preview must count the actual set, not the 2x2 bounding box");
        AtomicReference<ClaimRequest> seen = new AtomicReference<>();
        ClaimCommandHandler handler = new ClaimCommandHandler(harness.selections, request -> {
            seen.set(request);
            return CompletableFuture.completedFuture(ClaimOutcome.success(OWN_LAND));
        });

        handler.handle(player(), new String[]{"claim", "Home"}, harness.sink);

        assertTrue(seen.get() != null, "the irregular claim must reach the saga");
        assertEquals(lShape(), seen.get().chunks(),
                "the saga request must carry the actual irregular set");
        assertEquals(List.of("command.land.claim.success"), harness.keys);
        assertEquals(3, harness.vars.get(0).get("chunk_count"));
    }

    @Test
    void irregularExpandSubtractsOwnedFromActualShape() {
        Harness harness = new Harness();
        Set<ChunkKey> owned = Set.of(chunk(0, 0));
        harness.editSession(Set.of(chunk(0, 0), chunk(1, 0), chunk(0, 1)));
        AtomicReference<ExpandRequest> seen = new AtomicReference<>();
        ExpandCommandHandler handler = new ExpandCommandHandler(harness.selections,
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(ClaimOutcome.success(OWN_LAND));
                },
                null, sender -> Optional.of(OWN_LAND),
                target -> Optional.of(owned), target -> Optional.of(OwnerRef.player(PLAYER_ID)));

        handler.handle(player(), new String[]{"expand"}, harness.sink);

        assertTrue(seen.get() != null, "the irregular expansion must reach the saga");
        assertEquals(Set.of(chunk(1, 0), chunk(0, 1)), seen.get().delta(),
                "only the wilderness part of the irregular shape expands");
        assertEquals(List.of("command.land.expand.success"), harness.keys);
        assertEquals(2, harness.vars.get(0).get("chunk_count"));
    }
}
