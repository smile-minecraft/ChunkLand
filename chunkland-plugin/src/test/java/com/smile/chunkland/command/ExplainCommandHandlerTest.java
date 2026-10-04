package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ConfigSubjectPermissionLookup;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Red contract for {@code /land explain <action>}: MANAGE_PERMISSION gate,
 * same-provider resolver parity, SubLand XYZ precedence, fail-closed safety
 * and read-only wiring.
 */
class ExplainCommandHandlerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    /** Strong references for proxy worlds held weakly by Paper Location. */
    private static final List<World> PINNED_WORLDS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static World proxyWorld(UUID uid) {
        // Paper Location keeps its world through a weak reference: without a
        // pinned strong reference the proxy becomes eligible for GC and a
        // later getWorld() throws "World unloaded" non-deterministically.
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
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
                    return null;
                });
        PINNED_WORLDS.add(world);
        return world;
    }

    private static Player playerAt(UUID uuid, UUID worldId, double x, double y, double z,
            Map<String, Boolean> perms) {
        Location location = new Location(proxyWorld(worldId), x, y, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("hasPermission")) {
                        Object node = args == null || args.length == 0 ? null : args[0];
                        if (node instanceof String perm) {
                            return perms.getOrDefault(perm, false);
                        }
                        return false;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Producer";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Producer-proxy";
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
                    return null;
                });
    }

    private static CommandSender consoleSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("isPermissionSet")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
                    }
                    if (name.equals("sendMessage")) {
                        return null;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Console-proxy";
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
                    return null;
                });
    }

    private record Captured(String key, Map<String, Object> vars) {
    }

    private static final class CaptureSink implements ReplySink {
        final List<Captured> replies = new ArrayList<>();

        @Override
        public void reply(String key, Map<String, Object> vars) {
            replies.add(new Captured(key, Map.copyOf(vars)));
        }

        @Override
        public void reply(String key, Map<String, Object> vars, java.util.Locale locale) {
            replies.add(new Captured(key, Map.copyOf(vars)));
        }
    }

    private static LandSnapshot landWithSubland(LandId id, UUID owner, UUID world,
            SubLandSnapshot sub) {
        LandName name = LandName.of("Home");
        List<SubLandSnapshot> subs = sub == null ? List.of() : List.of(sub);
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)), subs,
                0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionBinding playerBinding(UUID actor, ProtectionActionType action,
            PermissionState state) {
        return new PermissionBinding(PermissionSubject.player(actor),
                new Permission(action, state));
    }

    private record Env(LandRegistryStore store, SnapshotPermissionContextProvider provider,
            LandId landId, SubLandId sublandId) {
    }

    /** Land with owner + one subland; caller supplies the auth layers. */
    private static Env envWith(LandAuthorisationSnapshot auth) {
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId sublandId = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(sublandId, landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), WORLD);
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithSubland(landId, OWNER, WORLD, sub))));
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty, () -> auth));
        return new Env(store, provider, landId, sublandId);
    }

    private static ExplainCommandHandler handlerOf(Env env) {
        return new ExplainCommandHandler(env.store()::snapshot, () -> env.provider());
    }

    @Test
    void subcommandPermissionAndGateWiringAbsent() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("explain"),
                "explain must be a registered /land subcommand");
        assertEquals("chunkland.command.land.explain",
                LandPermissions.forSubcommand("explain"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                com.smile.chunkland.protection.ManagementPermissionGate
                        .actionForSubcommand("explain"));
    }

    @Test
    void tabCompletionListsOnlyLegalActions() {
        CommandSender sender = consoleSender();
        List<String> all = LandCommand.tabComplete(sender,
                new String[] {"explain", ""});
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertTrue(all.contains(action.name()),
                    "completion must list " + action.name());
        }
        List<String> filtered = LandCommand.tabComplete(sender,
                new String[] {"explain", "BLOCK_"});
        assertTrue(filtered.contains("BLOCK_BREAK"));
        assertTrue(filtered.contains("BLOCK_PLACE"));
        assertFalse(filtered.contains("ENTRY"));
        List<String> none = LandCommand.tabComplete(sender,
                new String[] {"explain", "ZZZ_NOPE"});
        assertTrue(none.isEmpty());
    }

    @Test
    void strangerWithoutManagePermissionIsDenied() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        UUID stranger = UUID.randomUUID();
        Player player = playerAt(stranger, WORLD, 5.0, 64.0, 5.0, Map.of());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(player, new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void consoleSenderFailsClosed() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(consoleSender(),
                new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void unknownActionAndMissingArgFailClosed() {
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of());
        Env env = envWith(auth);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"explain", "FLY_FOREVER"}, sink);
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());

        CaptureSink usage = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"explain"}, usage);
        assertEquals("command.land.explain.usage", usage.replies.get(0).key());
    }

    @Test
    void wildernessFailsClosed() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        Player owner = playerAt(OWNER, WORLD, 500.0, 64.0, 500.0, Map.of());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void ownerExplainMatchesResolverOutcomeAndSource() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        Captured got = sink.replies.get(0);
        assertEquals("command.land.explain.result", got.key());
        assertEquals("BLOCK_BREAK", got.vars().get("action"));

        var direct = PermissionResolver.resolve(env.provider().provideAtBlock(OWNER,
                env.landId(), 5, 64, 5, ProtectionActionType.BLOCK_BREAK,
                env.store().snapshot()));
        assertEquals(direct.outcome().name(), got.vars().get("outcome"));
        assertEquals(direct.source().name(), got.vars().get("source"));
        assertEquals("GUARANTEE", got.vars().get("layer"));
    }

    @Test
    void sublandXyzPrecedenceThroughProductionPath() {
        UUID actor = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        Map<String, java.util.Set<UUID>> members = Map.of(group.toString(), Set.of(actor));
        PermissionBinding manage =
                playerBinding(actor, ProtectionActionType.MANAGE_PERMISSION,
                        PermissionState.ALLOW);
        PermissionBinding landAllow =
                playerBinding(actor, ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        PermissionBinding subDeny =
                playerBinding(actor, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY);
        // The land spans two chunks but the subland covers only the first
        // column, so block x=20 sits on the land yet outside every subland.
        LandId landId = new LandId(UUID.randomUUID());
        SubLandId sublandId = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(sublandId, landId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), WORLD);
        LandRegistryStore store = new LandRegistryStore();
        LandName wideName = LandName.of("Wide");
        store.publish(LandRegistry.from(List.of(new LandSnapshot(landId,
                wideName.displayName(), wideName.nameKey(), OwnerRef.player(OWNER),
                WORLD, Set.of(new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 1, 0)),
                List.of(sub), 0L, 0L, Instant.EPOCH, Instant.EPOCH))));
        LandAuthorisationSnapshot full = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(manage, landAllow)),
                Map.of(sublandId, List.of(subDeny)),
                members, Map.of());
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty, () -> full));
        ExplainCommandHandler handler =
                new ExplainCommandHandler(store::snapshot, () -> provider);

        CaptureSink inside = new CaptureSink();
        handler.handle(playerAt(actor, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, inside);
        assertEquals("command.land.explain.result", inside.replies.get(0).key());
        assertEquals("DENY", inside.replies.get(0).vars().get("outcome"));
        assertEquals("SUBLAND_BINDING", inside.replies.get(0).vars().get("layer"));

        CaptureSink outside = new CaptureSink();
        handler.handle(playerAt(actor, WORLD, 20.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, outside);
        assertEquals("command.land.explain.result", outside.replies.get(0).key());
        assertEquals("ALLOW", outside.replies.get(0).vars().get("outcome"));
        assertEquals("LAND_BINDING", outside.replies.get(0).vars().get("layer"));
    }

    @Test
    void entryBanDeniesAndGroupDenyBeatsDirectAllow() {
        UUID member = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        PermissionBinding groupAllowEntry = new PermissionBinding(
                PermissionSubject.group(group.toString()),
                new Permission(ProtectionActionType.ENTRY, PermissionState.ALLOW));
        PermissionBinding manage =
                playerBinding(member, ProtectionActionType.MANAGE_PERMISSION,
                        PermissionState.ALLOW);
        Map<String, java.util.Set<UUID>> members = Map.of(group.toString(), Set.of(member));
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithSubland(landId, OWNER, WORLD, null))));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(landId, Set.of(member)),
                Map.of(landId, List.of(manage, groupAllowEntry)),
                Map.of(), members, Map.of());
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty, () -> auth));
        ExplainCommandHandler handler =
                new ExplainCommandHandler(store::snapshot, () -> provider);
        CaptureSink sink = new CaptureSink();
        handler.handle(playerAt(member, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "ENTRY"}, sink);
        assertEquals("command.land.explain.result", sink.replies.get(0).key());
        assertEquals("DENY", sink.replies.get(0).vars().get("outcome"));
        assertEquals("LAND_BINDING", sink.replies.get(0).vars().get("layer"));
    }

    @Test
    void unloadedSnapshotFailsClosed() {
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithSubland(landId, OWNER, WORLD, null))));
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty,
                        LandAuthorisationSnapshot::unloaded));
        ExplainCommandHandler handler =
                new ExplainCommandHandler(store::snapshot, () -> provider);
        // Owner passes the gate via guarantee only when the provider chain is
        // verifiable; an unloaded auth must fail the explain closed on the
        // generic denial without land details.
        CaptureSink sink = new CaptureSink();
        handler.handle(playerAt(UUID.randomUUID(), WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
    }

    @Test
    void explainLeaksNoForeignPlayerDetails() {
        UUID actor = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        PermissionBinding manage =
                playerBinding(actor, ProtectionActionType.MANAGE_PERMISSION,
                        PermissionState.ALLOW);
        PermissionBinding target =
                playerBinding(actor, ProtectionActionType.DOOR_USE, PermissionState.ALLOW);
        PermissionBinding other =
                playerBinding(foreign, ProtectionActionType.DOOR_USE, PermissionState.DENY);
        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithSubland(landId, OWNER, WORLD, null))));
        LandAuthorisationSnapshot auth = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(landId, List.of(manage, target, other)),
                Map.of(), Map.of(), Map.of());
        SnapshotPermissionContextProvider provider = new SnapshotPermissionContextProvider(null,
                new ConfigSubjectPermissionLookup(
                        PermissionDefaultsSnapshot::empty, () -> auth));
        ExplainCommandHandler handler =
                new ExplainCommandHandler(store::snapshot, () -> provider);
        CaptureSink sink = new CaptureSink();
        handler.handle(playerAt(actor, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "DOOR_USE"}, sink);
        assertEquals("command.land.explain.result", sink.replies.get(0).key());
        String joined = sink.replies.get(0).vars().toString();
        assertFalse(joined.contains(foreign.toString()),
                "explain must not leak unrelated player ids");
        assertFalse(joined.contains(actor.toString()),
                "explain must not echo the actor id either");
    }

    @Test
    void explainCreatesNoSecondCacheAndCallsProviderEveryTime() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        AtomicInteger calls = new AtomicInteger();
        PermissionContextProvider counting = (actor, landId, action, snapshot) -> {
            calls.incrementAndGet();
            return env.provider().provide(actor, landId, action, snapshot);
        };
        ExplainCommandHandler handler =
                new ExplainCommandHandler(env.store()::snapshot, () -> counting);
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of());
        handler.handle(owner, new String[] {"explain", "BLOCK_BREAK"}, new CaptureSink());
        handler.handle(owner, new String[] {"explain", "BLOCK_BREAK"}, new CaptureSink());
        assertTrue(calls.get() >= 2,
                "explain must resolve through the shared provider on every call, never a second cache");
        for (var field : ExplainCommandHandler.class.getDeclaredFields()) {
            assertFalse(field.getType().getSimpleName().contains("Cache"),
                    "explain handler must not hold a decision cache: " + field);
        }
    }

    @Test
    void bilingualExplainKeysAreStrictMirrored() throws Exception {
        java.util.Set<String> en = explainKeys("en_US");
        java.util.Set<String> zh = explainKeys("zh_TW");
        assertEquals(en, zh, "explain key set must mirror between en_US and zh_TW");
        assertTrue(en.contains("command.land.explain.result"));
        assertTrue(en.contains("command.land.explain.usage"));
        assertTrue(en.contains("command.land.explain.failed"));
        assertTrue(en.contains("command.land.explain.console"));
        assertTrue(en.contains("command.land.explain.denied"));
    }

    private static java.util.Set<String> explainKeys(String tag) throws Exception {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.load(new java.io.File("src/main/resources/lang/" + tag + ".yml"));
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String key : cfg.getKeys(true)) {
            Object value = cfg.get(key);
            if (value instanceof String && key.startsWith("command.land.explain.")) {
                out.add(key);
            }
        }
        return out;
    }

    @Test
    void landRuleExplainMarksRuleLayer() {
        // The owner guarantee never rescues a LAND_RULE: the owner is bound
        // by environment rules like anyone else.
        var ctx = com.smile.chunkland.api.permission.PermissionContext
                .builder(ProtectionActionType.FIRE_SPREAD).isOwner(true)
                .landRule(PermissionState.DENY).build();
        var decision = PermissionResolver.resolve(ctx);
        var explained = com.smile.chunkland.protection.PermissionExplainService
                .explain(ctx, decision, null, false, false);
        assertEquals(com.smile.chunkland.protection.PermissionExplainLayer.LAND_RULE,
                explained.layer());
        assertEquals(PermissionState.DENY, explained.outcome());
    }

    // --- Red: authorized result must carry four flags ---

    @Test
    void authorizedResultCarriesFourFlags() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        Player owner = playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(owner, new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.result", sink.replies.get(0).key());
        Map<String, Object> vars = sink.replies.get(0).vars();
        assertTrue(vars.containsKey("coveringSubLandId"),
                "result must carry coveringSubLandId");
        assertTrue(vars.containsKey("isOwner"), "result must carry isOwner");
        assertTrue(vars.containsKey("adminBypass"), "result must carry adminBypass");
        assertTrue(vars.containsKey("steward"), "result must carry steward");
    }

    @Test
    void ownerStewardAdminFlagsAndCoveringInVars() {
        // Owner inside covering subland: covering id present, owner true.
        Env env = envWith(LandAuthorisationSnapshot.empty());
        CaptureSink inside = new CaptureSink();
        handlerOf(env).handle(playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, inside);
        assertEquals("command.land.explain.result", inside.replies.get(0).key());
        Map<String, Object> inVars = inside.replies.get(0).vars();
        assertEquals(env.sublandId().value().toString(), inVars.get("coveringSubLandId"));
        assertEquals(Boolean.TRUE, inVars.get("isOwner"));
        assertEquals(Boolean.FALSE, inVars.get("adminBypass"));
        assertEquals(Boolean.FALSE, inVars.get("steward"));

        // Outside every subland but on the land: covering is none, no leak.
        UUID actor = UUID.randomUUID();
        PermissionBinding manage =
                playerBinding(actor, ProtectionActionType.MANAGE_PERMISSION,
                        PermissionState.ALLOW);
        PermissionBinding landAllow =
                playerBinding(actor, ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW);
        LandId wideId = new LandId(UUID.randomUUID());
        SubLandId wideSub = new SubLandId(UUID.randomUUID());
        SubLandSnapshot sub = new SubLandSnapshot(wideSub, wideId, "Den",
                new Cuboid(0, 0, 0, 15, 255, 15), WORLD);
        LandRegistryStore wideStore = new LandRegistryStore();
        LandName wideName = LandName.of("Wide");
        wideStore.publish(LandRegistry.from(List.of(new LandSnapshot(wideId,
                wideName.displayName(), wideName.nameKey(), OwnerRef.player(OWNER),
                WORLD, Set.of(new ChunkKey(WORLD, 0, 0), new ChunkKey(WORLD, 1, 0)),
                List.of(sub), 0L, 0L, Instant.EPOCH, Instant.EPOCH))));
        LandAuthorisationSnapshot full = LandAuthorisationSnapshot.copyOf(
                Map.of(), Map.of(), Map.of(),
                Map.of(wideId, List.of(manage, landAllow)),
                Map.of(), Map.of(), Map.of());
        SnapshotPermissionContextProvider wideProvider =
                new SnapshotPermissionContextProvider(null,
                        new ConfigSubjectPermissionLookup(
                                PermissionDefaultsSnapshot::empty, () -> full));
        ExplainCommandHandler wideHandler =
                new ExplainCommandHandler(wideStore::snapshot, () -> wideProvider);
        CaptureSink outside = new CaptureSink();
        wideHandler.handle(playerAt(actor, WORLD, 20.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, outside);
        assertEquals("command.land.explain.result", outside.replies.get(0).key());
        assertEquals("none", outside.replies.get(0).vars().get("coveringSubLandId"));
        assertEquals(Boolean.FALSE, outside.replies.get(0).vars().get("isOwner"));

        // Steward on Server Land: steward true, layer STEWARD, no player leak.
        LandId serverId = new LandId(UUID.randomUUID());
        LandRegistryStore serverStore = new LandRegistryStore();
        LandName serverName = LandName.of("Server");
        serverStore.publish(LandRegistry.from(List.of(new LandSnapshot(serverId,
                serverName.displayName(), serverName.nameKey(), OwnerRef.server(),
                WORLD, Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                0L, 0L, Instant.EPOCH, Instant.EPOCH))));
        SnapshotPermissionContextProvider serverProvider =
                new SnapshotPermissionContextProvider(null,
                        new ConfigSubjectPermissionLookup(
                                PermissionDefaultsSnapshot::empty,
                                LandAuthorisationSnapshot::empty));
        ExplainCommandHandler serverHandler =
                new ExplainCommandHandler(serverStore::snapshot, () -> serverProvider);
        UUID stewardActor = UUID.randomUUID();
        Player steward = playerAt(stewardActor, WORLD, 5.0, 64.0, 5.0,
                Map.of(PluginManagementGateResolver.SERVER_LAND_STEWARD_NODE, true));
        CaptureSink stewardSink = new CaptureSink();
        serverHandler.handle(steward, new String[] {"explain", "BLOCK_BREAK"}, stewardSink);
        assertEquals("command.land.explain.result", stewardSink.replies.get(0).key());
        assertEquals(Boolean.TRUE, stewardSink.replies.get(0).vars().get("steward"));
        assertEquals("STEWARD", stewardSink.replies.get(0).vars().get("layer"));
        String joined = stewardSink.replies.get(0).vars().toString();
        assertFalse(joined.contains(stewardActor.toString()));

        // Admin bypass flag travels through the service without player details.
        var adminCtx = com.smile.chunkland.api.permission.PermissionContext
                .builder(ProtectionActionType.BLOCK_BREAK).adminBypass(true).build();
        var adminDecision = PermissionResolver.resolve(adminCtx);
        var adminExplained = com.smile.chunkland.protection.PermissionExplainService
                .explain(adminCtx, adminDecision, null, false, false);
        assertEquals(com.smile.chunkland.protection.PermissionExplainLayer.BYPASS,
                adminExplained.layer());
        assertTrue(adminExplained.adminBypass());
    }

    @Test
    void unauthorizedWildernessAndLandReturnIdenticalGenericDenial() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        UUID stranger = UUID.randomUUID();
        CaptureSink onLand = new CaptureSink();
        handlerOf(env).handle(playerAt(stranger, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, onLand);
        CaptureSink wild = new CaptureSink();
        handlerOf(env).handle(playerAt(stranger, WORLD, 500.0, 64.0, 500.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, wild);
        assertEquals(1, onLand.replies.size());
        assertEquals(1, wild.replies.size());
        assertEquals("command.land.explain.denied", onLand.replies.get(0).key());
        assertEquals("command.land.explain.denied", wild.replies.get(0).key());
        assertEquals(onLand.replies.get(0).vars(), wild.replies.get(0).vars(),
                "wilderness and land denial must be identical so land existence is not probed");
        assertTrue(onLand.replies.get(0).vars().isEmpty());
    }

    @Test
    void authorizedWildernessFailsClosedWithoutExistenceLeak() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        CaptureSink sink = new CaptureSink();
        handlerOf(env).handle(playerAt(OWNER, WORLD, 500.0, 64.0, 500.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, sink);
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.explain.denied", sink.replies.get(0).key());
        assertTrue(sink.replies.get(0).vars().isEmpty());
        String joined = sink.replies.get(0).key() + sink.replies.get(0).vars().toString();
        assertFalse(joined.contains("unknown_land"));
        assertFalse(joined.contains("BLOCK_BREAK"));
    }

    @Test
    void consoleInvalidUnloadedReturnSameGenericDenial() {
        Env env = envWith(LandAuthorisationSnapshot.empty());
        CaptureSink console = new CaptureSink();
        handlerOf(env).handle(consoleSender(),
                new String[] {"explain", "BLOCK_BREAK"}, console);
        assertEquals("command.land.explain.denied", console.replies.get(0).key());
        assertTrue(console.replies.get(0).vars().isEmpty());

        CaptureSink invalid = new CaptureSink();
        handlerOf(env).handle(playerAt(OWNER, WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "FLY_FOREVER"}, invalid);
        assertEquals("command.land.explain.denied", invalid.replies.get(0).key());
        assertTrue(invalid.replies.get(0).vars().isEmpty());

        LandId landId = new LandId(UUID.randomUUID());
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(landWithSubland(landId, OWNER, WORLD, null))));
        SnapshotPermissionContextProvider unloadedProvider =
                new SnapshotPermissionContextProvider(null,
                        new ConfigSubjectPermissionLookup(
                                PermissionDefaultsSnapshot::empty,
                                LandAuthorisationSnapshot::unloaded));
        ExplainCommandHandler unloaded =
                new ExplainCommandHandler(store::snapshot, () -> unloadedProvider);
        CaptureSink unloadedSink = new CaptureSink();
        unloaded.handle(playerAt(UUID.randomUUID(), WORLD, 5.0, 64.0, 5.0, Map.of()),
                new String[] {"explain", "BLOCK_BREAK"}, unloadedSink);
        assertEquals("command.land.explain.denied", unloadedSink.replies.get(0).key());
        assertTrue(unloadedSink.replies.get(0).vars().isEmpty());

        assertEquals(console.replies.get(0).key(), invalid.replies.get(0).key());
        assertEquals(console.replies.get(0).vars(), invalid.replies.get(0).vars());
        assertEquals(console.replies.get(0).key(), unloadedSink.replies.get(0).key());
    }

    @Test
    void bilingualExplainDeniedAndResultPlaceholdersStrictMirror() throws Exception {
        java.util.Set<String> en = explainKeys("en_US");
        java.util.Set<String> zh = explainKeys("zh_TW");
        assertEquals(en, zh, "explain key set must mirror between en_US and zh_TW");
        assertTrue(en.contains("command.land.explain.denied"));
        assertTrue(en.contains("command.land.explain.result"));
        for (String tag : new String[] {"en_US", "zh_TW"}) {
            org.bukkit.configuration.file.YamlConfiguration cfg =
                    new org.bukkit.configuration.file.YamlConfiguration();
            cfg.load(new java.io.File("src/main/resources/lang/" + tag + ".yml"));
            String result = cfg.getString("command.land.explain.result");
            assertTrue(result.contains("<coveringSubLandId>"),
                    tag + " result must carry <coveringSubLandId>");
            assertTrue(result.contains("<isOwner>"), tag + " result must carry <isOwner>");
            assertTrue(result.contains("<adminBypass>"),
                    tag + " result must carry <adminBypass>");
            assertTrue(result.contains("<steward>"), tag + " result must carry <steward>");
            validateStrict("command.land.explain.result", result);
            String denied = cfg.getString("command.land.explain.denied");
            validateStrict("command.land.explain.denied", denied);
        }
        assertEquals(placeholdersOf("en_US", "command.land.explain.result"),
                placeholdersOf("zh_TW", "command.land.explain.result"));
        assertEquals(placeholdersOf("en_US", "command.land.explain.denied"),
                placeholdersOf("zh_TW", "command.land.explain.denied"));
    }

    private static java.util.Set<String> placeholdersOf(String tag, String key)
            throws Exception {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.load(new java.io.File("src/main/resources/lang/" + tag + ".yml"));
        String template = cfg.getString(key);
        java.util.Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<([A-Za-z_]+)>").matcher(template);
        while (m.find()) {
            out.add(m.group(1));
        }
        // Standard MiniMessage color tags are not placeholders.
        out.remove("green");
        out.remove("red");
        out.remove("yellow");
        out.remove("gray");
        out.remove("white");
        return out;
    }

    private static void validateStrict(String key, String template) throws Exception {
        var m = ChunkLandMessagePipeline.class.getDeclaredMethod(
                "validateTemplateStrict", String.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(null, key, template);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        }
    }
}
