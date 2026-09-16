package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.command.ExpandCommandHandler;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Server namespace isolation for {@code /land expand}.
 *
 * <p>A steward passes the management gate on Server Land, so the handler
 * must build the saga request from the target snapshot's owner (Server
 * targets carry the Server owner, exactly like shrink and delete do). A
 * player actor expanding Server Land with a player owner must keep failing
 * the validator's owner check.
 */
class ExpandServerStewardCommandTest {

    @TempDir java.nio.file.Path temp;

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class Reply {
        final String key;
        final Map<String, Object> vars;

        Reply(String key, Map<String, Object> vars) {
            this.key = key;
            this.vars = Map.copyOf(vars);
        }
    }

    static final class CollectSink implements ReplySink {
        final List<Reply> replies = new ArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    private static ChunkKey chunk(UUID world, int x, int z) {
        return new ChunkKey(world, x, z);
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "TestPlayer";
                    }
                    if (name.equals("equals") || name.equals("hashCode")
                            || name.equals("toString")) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Player-proxy:" + id;
                        };
                    }
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) {
                        return false;
                    }
                    if (result == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private SelectionSessionManager selections() {
        SelectionStructureRevisionLookup structures = landId -> OptionalLong.of(0L);
        return new SelectionSessionManager(
                (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                SelectionNotifier.noop(),
                (SelectionClock) () -> NOW,
                Duration.ofMinutes(10),
                ignored -> Optional.of("world"),
                structures);
    }

    private void selectAll(UUID actor, UUID world, LandId target, Set<ChunkKey> selected,
            SelectionSessionManager sessions) {
        SelectionSession initial = SelectionSession.initial(
                actor, world, SelectionMode.CREATE_LAND,
                Optional.of(target), Optional.empty(),
                Optional.of(new SelectionPoint(world, 0, 64, 0)),
                Optional.of(new SelectionPoint(world, 16, 64, 16)),
                0, NOW);
        SelectionSession stamped = sessions.start(initial);
        sessions.updateSelection(actor, stamped, new SelectionUpdate(
                        initial.pointA(), initial.pointB(), selected, Map.of()))
                .orElseThrow(() -> new IllegalStateException("selection update must succeed"));
    }

    private Function<LandId, Optional<OwnerRef>> targetOwner(LandRegistryStore registryStore) {
        return landId -> {
            var snapshot = registryStore.snapshot();
            if (snapshot == null || landId == null) {
                return Optional.empty();
            }
            var land = snapshot.land(landId);
            if (land == null || land.ownerRef() == null) {
                return Optional.empty();
            }
            return Optional.of(land.ownerRef());
        };
    }

    @Test
    void stewardExpandOnServerLandCarriesServerOwner() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"))) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            LandRegistryStore registryStore = new LandRegistryStore();
            UUID steward = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = new LandId(UUID.randomUUID());
            Set<ChunkKey> owned = Set.of(chunk(world, 0, 0));
            String displayName = "Spawn " + land.value().toString().substring(0, 8);
            lands.save(new LandSnapshot(land, displayName, LandName.normalize(displayName),
                            OwnerRef.server(), world, owned, List.of(), 0, 0, NOW, NOW))
                    .toCompletableFuture().join();
            for (ChunkKey key : owned) {
                chunks.addChunk(land, key, 64, UUID.randomUUID(), 0L)
                        .toCompletableFuture().join();
            }
            new com.smile.chunkland.claim.RuntimeRegistryRebuilder(lands, registryStore)
                    .rebuild().toCompletableFuture().join();

            SelectionSessionManager sessions = selections();
            ChunkKey fresh = chunk(world, 1, 0);
            selectAll(steward, world, land, Set.of(chunk(world, 0, 0), fresh), sessions);

            AtomicReference<ExpandRequest> seen = new AtomicReference<>();
            ExpandCommandHandler handler = new ExpandCommandHandler(sessions,
                    request -> {
                        seen.set(request);
                        return CompletableFuture.completedFuture(ClaimOutcome.success(land));
                    },
                    null, sender -> Optional.of(land),
                    target -> Optional.of(owned), targetOwner(registryStore));
            CollectSink sink = new CollectSink();
            handler.handle(player(steward), new String[]{"expand"}, sink);

            assertEquals(1, sink.replies.size(), "handler must reply exactly once");
            assertEquals("command.land.expand.success", sink.replies.get(0).key);
            assertTrue(seen.get() != null && seen.get().owner() instanceof OwnerRef.ServerOwnerRef,
                    "the saga request must carry the Server owner on Server Land");
        }
    }

    @Test
    void playerOwnerRequestOnServerTargetKeepsOwnerMismatch() throws Exception {
        LandRegistryStore registryStore = new LandRegistryStore();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        LandName name = LandName.of("Spawn");
        registryStore.publish(com.smile.chunkland.runtime.index.LandRegistry.from(List.of(
                new LandSnapshot(target, name.displayName(), name.nameKey(), OwnerRef.server(),
                        world, Set.of(chunk(world, 0, 0)), List.of(), 0L, 0L, NOW, NOW))));

        SnapshotExpandValidator validator = new SnapshotExpandValidator(registryStore,
                actor -> OptionalLong.of(0L),
                actor -> OptionalLong.of(0L),
                chunk -> 64,
                owner -> 0L,
                landId -> OptionalLong.of(0L));
        ExpandRequest forged = new ExpandRequest(OwnerRef.player(UUID.randomUUID()),
                UUID.randomUUID(), world, target, Set.of(chunk(world, 1, 0)), 0L, 0L, 0L);
        try {
            validator.validate(forged);
            assertTrue(false, "a player owner on a Server target must fail validation");
        } catch (ClaimRejectedException rejected) {
            assertEquals("expand.owner_mismatch", rejected.getMessage());
        }
    }
}
