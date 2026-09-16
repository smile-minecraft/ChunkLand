package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.ClaimOutcome;
import com.smile.chunkland.claim.ClaimRejectedException;
import com.smile.chunkland.claim.ExpandRequest;
import com.smile.chunkland.claim.SnapshotExpandValidator;
import com.smile.chunkland.command.ExpandCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.PluginManagementGateResolver;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.rule.LandRuleService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Gate-to-handler snapshot race for {@code /land expand}.
 *
 * <p>The dispatcher authorises one immutable snapshot while the handler used
 * to resolve the target owner from another live snapshot. When the land
 * changes namespace between the two reads (Player converts to Server Land, or
 * the reverse), a plain player could expand Server Land and a steward could
 * reach into Player Land. The handler must deny fail-closed on that
 * divergence without invoking the saga runner; the validator's owner check
 * stays as the second line of defence behind it.
 */
class ExpandGateSnapshotRaceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    /**
     * Strong references to proxy worlds. Paper 1.21 holds a
     * {@code Location}'s world weakly, so a proxy world without a strong
     * reference can be collected mid-test and every {@code getWorld()} then
     * fails with {@code World unloaded}. Retaining them here keeps location
     * resolution deterministic.
     */
    private final List<Object> retainedWorlds = new ArrayList<>();

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

    private static World proxyWorld(UUID uid) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) {
                        return uid;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "World-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    if (rt == float.class) {
                        return 0f;
                    }
                    return null;
                });
    }

    private World retainWorld(UUID uid) {
        World world = proxyWorld(uid);
        retainedWorlds.add(world);
        return world;
    }

    private static Player player(UUID id, boolean steward, World world) {
        Location location = new Location(world, 5.0, 64.0, 5.0);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return id;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        if (PluginManagementGateResolver.SERVER_LAND_STEWARD_NODE.equals(node)) {
                            return steward;
                        }
                        if (node instanceof String perm && perm.startsWith("chunkland.")) {
                            return true;
                        }
                        return false;
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

    private static LandSnapshot playerLand(LandId id, UUID owner, UUID world) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), world, Set.of(chunk(world, 0, 0)),
                List.of(), 0L, 0L, NOW, NOW);
    }

    private static LandSnapshot serverLand(LandId id, UUID world) {
        LandName name = LandName.of("Spawn");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.server(), world, Set.of(chunk(world, 0, 0)),
                List.of(), 0L, 0L, NOW, NOW);
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

    private Function<LandId, Optional<OwnerRef>> liveTargetOwner(LandRegistryStore live) {
        return landId -> {
            var snapshot = live.snapshot();
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

    private ExpandCommandHandler.TargetChunkLookup liveTargetChunks(LandRegistryStore live) {
        return landId -> {
            var snapshot = live.snapshot();
            if (snapshot == null || landId == null) {
                return Optional.empty();
            }
            var land = snapshot.land(landId);
            if (land == null || land.chunks() == null) {
                return Optional.empty();
            }
            return Optional.of(land.chunks());
        };
    }

    private LandCommand dispatchOver(LandCommand.Handler expandHandler,
            ManagementGateResolver resolver, List<String> keys) {
        return new LandCommand(Map.of("expand", expandHandler),
                (sender, pipeline) -> new ReplySink() {
                    @Override
                    public void reply(String key, Map<String, Object> vars) {
                        keys.add(key);
                    }

                    @Override
                    public void reply(String key, Map<String, Object> vars, Locale locale) {
                        keys.add(key);
                    }
                }, resolver);
    }

    /**
     * Gate resolver pinned to one immutable snapshot object: whatever the
     * live store publishes afterwards, the dispatcher always authorises this
     * exact snapshot — the deterministic model of a conversion landing
     * between the gate and the handler.
     */
    private static ManagementGateResolver fixedGate(UUID actor, LandId target,
            LandRegistry authorised, boolean steward) {
        PermissionContextProvider provider =
                new SnapshotPermissionContextProvider(LandRuleService.defaults(), null);
        ManagementGateResolver.Request request = new ManagementGateResolver.Request(
                actor, target, authorised, false, steward, provider);
        return (sender, action, args) -> Optional.of(request);
    }

    @Test
    void playerToServerConversionDeniesWithoutRunner() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        // Gate authorises the Player snapshot; the live store converts to
        // Server Land before the handler runs.
        LandRegistry authorised = LandRegistry.from(List.of(playerLand(target, actor, world)));
        LandRegistryStore live = new LandRegistryStore();
        live.publish(authorised);
        ManagementGateResolver resolver = fixedGate(actor, target, authorised, false);
        live.publish(LandRegistry.from(List.of(serverLand(target, world))));

        SelectionSessionManager sessions = selections();
        selectAll(actor, world, target, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), sessions);

        AtomicBoolean runnerCalled = new AtomicBoolean(false);
        ExpandCommandHandler handler = new ExpandCommandHandler(sessions,
                request -> {
                    runnerCalled.set(true);
                    return CompletableFuture.completedFuture(ClaimOutcome.success(target));
                },
                null, sender -> Optional.of(target),
                liveTargetChunks(live), liveTargetOwner(live));

        List<String> keys = new ArrayList<>();
        LandCommand command = dispatchOver(handler, resolver, keys);
        World worldView = retainWorld(world);
        assertTrue(command.dispatch(player(actor, false, worldView), new String[]{"expand"}, null));

        assertFalse(runnerCalled.get(),
                "a Player-to-Server conversion between gate and handler must deny before the saga runs");
        assertEquals(1, keys.size(), "the race must deny fail-closed with exactly one reply");
        assertEquals("command.land.expand.failed", keys.get(0));
    }

    @Test
    void serverToPlayerConversionDeniesWithoutRunner() {
        UUID steward = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        // Gate authorises the steward on the Server snapshot; the live store
        // converts to someone else's Player Land before the handler runs.
        LandRegistry authorised = LandRegistry.from(List.of(serverLand(target, world)));
        LandRegistryStore live = new LandRegistryStore();
        live.publish(authorised);
        ManagementGateResolver resolver = fixedGate(steward, target, authorised, true);
        live.publish(LandRegistry.from(List.of(playerLand(target, UUID.randomUUID(), world))));

        SelectionSessionManager sessions = selections();
        selectAll(steward, world, target, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), sessions);

        AtomicBoolean runnerCalled = new AtomicBoolean(false);
        ExpandCommandHandler handler = new ExpandCommandHandler(sessions,
                request -> {
                    runnerCalled.set(true);
                    return CompletableFuture.completedFuture(ClaimOutcome.success(target));
                },
                null, sender -> Optional.of(target),
                liveTargetChunks(live), liveTargetOwner(live));

        List<String> keys = new ArrayList<>();
        LandCommand command = dispatchOver(handler, resolver, keys);
        World worldView = retainWorld(world);
        assertTrue(command.dispatch(player(steward, true, worldView), new String[]{"expand"}, null));

        assertFalse(runnerCalled.get(),
                "a Server-to-Player conversion between gate and handler must deny before the saga runs");
        assertEquals(1, keys.size(), "the race must deny fail-closed with exactly one reply");
        assertEquals("command.land.expand.failed", keys.get(0));
    }

    @Test
    void steadyPlayerExpandStillReachesRunner() {
        UUID actor = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        LandRegistryStore pinned = new LandRegistryStore();
        pinned.publish(LandRegistry.from(List.of(playerLand(target, actor, world))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(pinned);

        SelectionSessionManager sessions = selections();
        selectAll(actor, world, target, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), sessions);

        AtomicReference<ExpandRequest> seen = new AtomicReference<>();
        ExpandCommandHandler handler = new ExpandCommandHandler(sessions,
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(ClaimOutcome.success(target));
                },
                null, sender -> Optional.of(target),
                liveTargetChunks(pinned), liveTargetOwner(pinned));

        List<String> keys = new ArrayList<>();
        LandCommand command = dispatchOver(handler, resolver, keys);
        World worldView = retainWorld(world);
        assertTrue(command.dispatch(player(actor, false, worldView), new String[]{"expand"}, null));
        assertTrue(seen.get() != null, "steady-state owner expand must still reach the runner");
        assertTrue(seen.get().owner() instanceof OwnerRef.PlayerOwnerRef,
                "steady-state Player Land must carry the player owner");
        assertEquals(seen.get().owner().key(), pinned.snapshot().land(target).ownerRef().key(),
                "the request owner must come from the same snapshot the gate authorised");
    }

    @Test
    void steadyStewardExpandStillReachesRunner() {
        UUID steward = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());

        LandRegistryStore pinned = new LandRegistryStore();
        pinned.publish(LandRegistry.from(List.of(serverLand(target, world))));
        ManagementGateResolver resolver = ChunkLandPlugin.buildManagementGateResolver(pinned);

        SelectionSessionManager sessions = selections();
        selectAll(steward, world, target, Set.of(chunk(world, 0, 0), chunk(world, 1, 0)), sessions);

        AtomicReference<ExpandRequest> seen = new AtomicReference<>();
        ExpandCommandHandler handler = new ExpandCommandHandler(sessions,
                request -> {
                    seen.set(request);
                    return CompletableFuture.completedFuture(ClaimOutcome.success(target));
                },
                null, sender -> Optional.of(target),
                liveTargetChunks(pinned), liveTargetOwner(pinned));

        List<String> keys = new ArrayList<>();
        LandCommand command = dispatchOver(handler, resolver, keys);
        World worldView = retainWorld(world);
        assertTrue(command.dispatch(player(steward, true, worldView), new String[]{"expand"}, null));
        assertTrue(seen.get() != null, "steady-state steward expand must still reach the runner");
        assertTrue(seen.get().owner() instanceof OwnerRef.ServerOwnerRef,
                "steady-state Server Land must carry the Server owner");
    }

    @Test
    void validatorStillRejectsServerOwnerOnPlayerTarget() {
        UUID world = UUID.randomUUID();
        LandId target = new LandId(UUID.randomUUID());
        LandName name = LandName.of("Home");
        UUID owner = UUID.randomUUID();
        LandRegistryStore live = new LandRegistryStore();
        live.publish(LandRegistry.from(List.of(
                new LandSnapshot(target, name.displayName(), name.nameKey(), OwnerRef.player(owner),
                        world, Set.of(chunk(world, 0, 0)), List.of(), 0L, 0L, NOW, NOW))));

        SnapshotExpandValidator validator = new SnapshotExpandValidator(live,
                actor -> OptionalLong.of(0L),
                actor -> OptionalLong.of(0L),
                chunk -> 64,
                ignored -> 0L,
                landId -> OptionalLong.of(0L));
        ExpandRequest forged = new ExpandRequest(OwnerRef.server(),
                UUID.randomUUID(), world, target, Set.of(chunk(world, 1, 0)), 0L, 0L, 0L);
        try {
            validator.validate(forged);
            assertTrue(false, "a Server owner on a Player target must fail validation");
        } catch (ClaimRejectedException rejected) {
            assertEquals("expand.owner_mismatch", rejected.getMessage());
        }
    }
}
