package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionContext;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

/**
 * Guards the right-click classification of item-holding blocks.
 *
 * <p>Dispensers, droppers, crafters, copper chests, and the other blocks
 * listed here all store items a player can reach through right-click, so a
 * stranger must be denied while the owner (or anyone the engine allows)
 * passes. The name sweep below keeps the hard-coded sets honest: any future
 * block whose name matches a known item-holding pattern fails loudly instead
 * of silently staying vanilla.
 */
class ProtectionContainerClassificationTest {

    /** Every block this milestone adds to the container classification. */
    private static final List<Material> NEW_CONTAINER_BLOCKS = List.of(
            Material.DISPENSER,
            Material.DROPPER,
            Material.CRAFTER,
            Material.COPPER_CHEST,
            Material.EXPOSED_COPPER_CHEST,
            Material.WEATHERED_COPPER_CHEST,
            Material.OXIDIZED_COPPER_CHEST,
            Material.WAXED_COPPER_CHEST,
            Material.WAXED_EXPOSED_COPPER_CHEST,
            Material.WAXED_WEATHERED_COPPER_CHEST,
            Material.WAXED_OXIDIZED_COPPER_CHEST,
            Material.CHISELED_BOOKSHELF,
            Material.JUKEBOX,
            Material.LECTERN,
            Material.DECORATED_POT,
            Material.COMPOSTER,
            Material.VAULT);

    /**
     * Name fragments of block kinds whose right-click reaches stored items.
     * Checked against every {@link Material} so a future addition (for
     * example a new {@code *_DISPENSER} variant) fails this test instead of
     * silently staying unprotected. Item-only kinds (boats, rafts, minecarts)
     * and legacy constants never appear as a clicked block and are excluded.
     */
    private static final List<String> CONTAINER_NAME_HINTS = List.of(
            "CHEST", "SHULKER_BOX", "DISPENSER", "DROPPER", "CRAFTER",
            "BARREL", "HOPPER", "VAULT", "JUKEBOX", "LECTERN",
            // Plain BOOKSHELF is decoration with no right-click use; only the
            // chiseled variant stores books.
            "CHISELED_BOOKSHELF", "DECORATED_POT", "COMPOSTER");

    /** Same idea for crafting and processing blocks ({@code WORKSTATION_USE}). */
    private static final List<String> WORKSTATION_NAME_HINTS = List.of(
            "FURNACE", "BREWING_STAND", "ENCHANTING_TABLE", "CRAFTING_TABLE",
            "CARTOGRAPHY_TABLE", "STONECUTTER", "SMITHING_TABLE",
            "GRINDSTONE", "BEACON", "ANVIL", "LOOM");

    private static World worldProxy(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    private static Block blockProxy(World world, int x, int y, int z, Material type) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld": return world;
                        case "getX": return x;
                        case "getY": return y;
                        case "getZ": return z;
                        case "getType": return type;
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeBlock";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static Player playerProxy(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private record Fixture(UUID worldId, LandId landId, UUID owner, LandRegistryStore store, World world) {
    }

    private static Fixture fixture() {
        UUID worldId = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(
                new LandSnapshot(landId, "TestLand", "testland", OwnerRef.player(owner),
                        worldId, Set.of(new ChunkKey(worldId, 0, 0)),
                        List.of(), 0, 0, Instant.now(), Instant.now()))));
        return new Fixture(worldId, landId, owner, store, worldProxy(worldId));
    }

    private static ProtectionEngine engineFor(LandRegistryStore store,
                                              ProtectionActionType action,
                                              PermissionState state,
                                              AtomicReference<ProtectionActionType> seen,
                                              AtomicInteger consults) {
        return new ProtectionEngine(store::snapshot, (actor, landId, a, snapshot) -> {
            if (seen != null) {
                seen.set(a);
            }
            if (consults != null) {
                consults.incrementAndGet();
            }
            if (a.decisionSource() == DecisionSource.LAND_RULE) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, a == action ? state : PermissionState.INHERIT);
            }
            if (a != action) {
                return new PermissionContext(a, false, List.of(),
                        PermissionState.INHERIT, PermissionState.INHERIT);
            }
            return new PermissionContext(a, false, List.of(), state, PermissionState.INHERIT);
        });
    }

    @Test
    void newContainerBlocksMapToContainerOpen() {
        assertEquals(17, NEW_CONTAINER_BLOCKS.size(),
                "the newly classified list must stay complete; update the sweep hints too");
        for (Material kind : NEW_CONTAINER_BLOCKS) {
            assertEquals(ProtectionActionType.CONTAINER_OPEN,
                    ProtectionListener.interactAction(kind),
                    kind + " holds items: must route to CONTAINER_OPEN, not stay vanilla");
        }
    }

    @Test
    void strangerDeniedAndAuthorisedPassesForEachNewContainerBlock() {
        Fixture fx = fixture();
        UUID stranger = UUID.randomUUID();
        Player strangerPlayer = playerProxy(stranger);
        Player ownerPlayer = playerProxy(fx.owner());

        for (Material kind : NEW_CONTAINER_BLOCKS) {
            AtomicReference<ProtectionActionType> seen = new AtomicReference<>();
            ProtectionListener denying = new ProtectionListener(
                    engineFor(fx.store(), ProtectionActionType.CONTAINER_OPEN,
                            PermissionState.DENY, seen, null));
            PlayerInteractEvent denied = new PlayerInteractEvent(
                    strangerPlayer, Action.RIGHT_CLICK_BLOCK, null,
                    blockProxy(fx.world(), 5, 64, 5, kind), BlockFace.UP);
            denying.onPlayerInteract(denied);
            assertTrue(denied.isCancelled(), kind + ": stranger DENY must cancel");
            assertEquals(ProtectionActionType.CONTAINER_OPEN, seen.get(),
                    kind + " must route to CONTAINER_OPEN");

            ProtectionListener allowing = new ProtectionListener(
                    engineFor(fx.store(), ProtectionActionType.CONTAINER_OPEN,
                            PermissionState.ALLOW, null, null));
            PlayerInteractEvent allowed = new PlayerInteractEvent(
                    ownerPlayer, Action.RIGHT_CLICK_BLOCK, null,
                    blockProxy(fx.world(), 5, 64, 5, kind), BlockFace.UP);
            allowing.onPlayerInteract(allowed);
            assertFalse(allowed.isCancelled(), kind + ": authorised ALLOW must pass");
        }
    }

    private static boolean isItemOnlyOrLegacy(String name) {
        return name.startsWith("LEGACY_")
                || name.endsWith("_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("_MINECART")
                || name.endsWith("_SPAWN_EGG")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_SMITHING_TEMPLATE");
    }

    private static boolean matchesAny(String name, List<String> hints) {
        for (String hint : hints) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void materialSweepDetectsUnclassifiedItemHoldingBlocks() {
        int swept = 0;
        for (Material kind : Material.values()) {
            String name = kind.name();
            if (isItemOnlyOrLegacy(name)) {
                continue;
            }
            if (matchesAny(name, CONTAINER_NAME_HINTS)) {
                swept++;
                assertEquals(ProtectionActionType.CONTAINER_OPEN,
                        ProtectionListener.interactAction(kind),
                        kind + " looks like an item-holding block but is not classified: "
                                + "add it to the container sets instead of leaving it vanilla");
            } else if (matchesAny(name, WORKSTATION_NAME_HINTS)) {
                swept++;
                assertEquals(ProtectionActionType.WORKSTATION_USE,
                        ProtectionListener.interactAction(kind),
                        kind + " looks like a crafting/processing block but is not classified: "
                                + "add it to the workstation set instead of leaving it vanilla");
            }
        }
        assertTrue(swept >= NEW_CONTAINER_BLOCKS.size(),
                "sweep must cover at least the newly classified blocks, swept=" + swept);
    }

    @Test
    void allowPathDoesNoExtraWork() {
        Fixture fx = fixture();
        Player player = playerProxy(UUID.randomUUID());

        AtomicInteger consults = new AtomicInteger();
        ProtectionListener listener = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.CONTAINER_OPEN,
                        PermissionState.ALLOW, null, consults));
        PlayerInteractEvent allowed = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null,
                blockProxy(fx.world(), 5, 64, 5, Material.DISPENSER), BlockFace.UP);
        listener.onPlayerInteract(allowed);
        assertFalse(allowed.isCancelled());
        assertEquals(1, consults.get(),
                "ALLOW path must consult the engine exactly once: no parsing, "
                        + "no chunk load, no extra lookup");

        AtomicInteger vanillaConsults = new AtomicInteger();
        ProtectionListener vanilla = new ProtectionListener(
                engineFor(fx.store(), ProtectionActionType.BLOCK_BREAK,
                        PermissionState.DENY, null, vanillaConsults));
        PlayerInteractEvent plain = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null,
                blockProxy(fx.world(), 5, 64, 5, Material.STONE), BlockFace.UP);
        vanilla.onPlayerInteract(plain);
        assertFalse(plain.isCancelled(), "unprotected kinds stay vanilla");
        assertEquals(0, vanillaConsults.get(),
                "unprotected kinds must not consult the engine at all");
    }
}
