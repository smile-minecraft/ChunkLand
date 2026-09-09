package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.PluginManagementGateResolver;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Snapshot consistency for the management gate: target resolution and the
 * gate decision must observe the same already-acquired immutable snapshot,
 * so a volatile publish between two reads can never split them.
 */
class ManagementGateSnapshotConsistencyTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

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

    private static Player playerAt(UUID uuid, UUID worldId, double x, double z) {
        Location location = new Location(proxyWorld(worldId), x, 64.0, z);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("getLocation")) {
                        return location;
                    }
                    if (name.equals("hasPermission")) {
                        return false;
                    }
                    if (name.equals("getName")) {
                        return "Producer";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("sendMessage")) {
                        return null;
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

    private static LandSnapshot landAt(LandId id, UUID owner, UUID world, String name) {
        LandName landName = LandName.of(name);
        return new LandSnapshot(id, landName.displayName(), landName.nameKey(),
                OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void targetAndGateShareOneSnapshotRead() {
        LandId first = new LandId(UUID.randomUUID());
        LandId second = new LandId(UUID.randomUUID());
        LandRegistry snapshotA = LandRegistry.from(List.of(landAt(first, OWNER, WORLD, "HomeA")));
        LandRegistry snapshotB = LandRegistry.from(List.of(landAt(second, OWNER, WORLD, "HomeB")));
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<LandRegistry> targetSeen = new AtomicReference<>();
        // A publish racing the resolve would flip the volatile store between
        // two reads; the resolver must read once and hand that same instance
        // to the target, so the second published version is never observed.
        List<LandRegistry> versions = List.of(snapshotA, snapshotB);
        PluginManagementGateResolver.TargetLandResolver targets =
                (sender, action, args, snapshot) -> {
                    targetSeen.set(snapshot);
                    if (snapshot == null) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(snapshot.findLandId(WORLD, 0, 0));
                };
        PluginManagementGateResolver resolver = new PluginManagementGateResolver(
                () -> versions.get(Math.min(reads.getAndIncrement(), 1)),
                () -> new SnapshotPermissionContextProvider(null, null),
                targets);
        Optional<ManagementGateResolver.Request> resolved = resolver.resolve(
                playerAt(OWNER, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"});
        assertTrue(resolved.isPresent(), "land under the player must resolve");
        assertEquals(1, reads.get(), "resolver must take exactly one volatile snapshot read");
        assertSame(resolved.get().snapshot(), targetSeen.get(),
                "target resolution must use the already-acquired snapshot, never re-read the store");
        assertSame(snapshotA, resolved.get().snapshot(),
                "the single read must be the first published version");
        assertEquals(first, resolved.get().landId(),
                "land id must come from the same snapshot the gate will judge");
        assertTrue(resolved.get().snapshot().land(first) != null,
                "resolved land must exist in the snapshot handed to the gate");
    }

    @Test
    void currentLocationResolvesAgainstTheGivenSnapshot() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(landAt(id, OWNER, WORLD, "Home")));
        PluginManagementGateResolver.TargetLandResolver targets =
                PluginManagementGateResolver.TargetLandResolver.currentLocation();
        Optional<LandId> found = targets.resolveTarget(
                playerAt(OWNER, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                new String[]{"delete"}, snapshot);
        assertEquals(Optional.of(id), found, "current location must resolve from the given snapshot");
        assertTrue(targets.resolveTarget(
                        playerAt(OWNER, WORLD, 5.0, 5.0), ProtectionActionType.DELETE_LAND,
                        new String[]{"delete"}, null).isEmpty(),
                "null snapshot must fail closed without touching the store");
        assertTrue(targets.resolveTarget(
                        playerAt(OWNER, WORLD, 100.0, 100.0), ProtectionActionType.DELETE_LAND,
                        new String[]{"delete"}, snapshot).isEmpty(),
                "wilderness in the same snapshot must stay unresolved");
    }
}
