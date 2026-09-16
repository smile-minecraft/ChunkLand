package com.smile.chunkland.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

/**
 * Red: production assembly must resolve the Legacy provider through the
 * {@link ServicesManager} and fail closed on every defect — never hardcode
 * the unavailable bridge when a live provider exists.
 */
class VaultServiceDiscoveryTest {

    private static Economy fakeEconomy(boolean enabled, String name) {
        return (Economy) Proxy.newProxyInstance(VaultServiceDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {Economy.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> enabled;
                    case "getName" -> name;
                    default -> null;
                });
    }

    private static Plugin fakePlugin() {
        return (Plugin) Proxy.newProxyInstance(VaultServiceDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> null);
    }

    private static ServicesManager servicesReturning(Object registration) {
        return (ServicesManager) Proxy.newProxyInstance(
                VaultServiceDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")
                            && args != null && args.length == 1) {
                        return registration;
                    }
                    return null;
                });
    }

    @Test
    void liveLegacyRegistrationResolvesToRealBridge() {
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, fakeEconomy(true, "AceEconomy"), ServicePriority.Normal, fakePlugin());

        VaultBridge bridge = VaultServiceDiscovery.resolve(
                servicesReturning(registration), id -> null);

        assertInstanceOf(BukkitVaultBridge.class, bridge);
        assertTrue(bridge.isAvailable());
        assertEquals("vault:AceEconomy", bridge.providerId());
    }

    @Test
    void missingRegistrationFailsClosed() {
        VaultBridge bridge =
                VaultServiceDiscovery.resolve(servicesReturning(null), id -> null);

        assertFalse(bridge.isAvailable());
        assertFalse(bridge.withdraw(UUID.randomUUID(), 1.0, UUID.randomUUID()).success());
    }

    @Test
    void nullServicesFailsClosed() {
        VaultBridge bridge = VaultServiceDiscovery.resolve(null, id -> null);

        assertFalse(bridge.isAvailable());
    }

    @Test
    void nullPlayerLookupFailsClosed() {
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, fakeEconomy(true, "AceEconomy"), ServicePriority.Normal, fakePlugin());

        VaultBridge bridge = VaultServiceDiscovery.resolve(servicesReturning(registration), null);

        assertFalse(bridge.isAvailable());
    }

    @Test
    void disabledProviderFailsClosed() {
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, fakeEconomy(false, "AceEconomy"), ServicePriority.Normal, fakePlugin());

        VaultBridge bridge = VaultServiceDiscovery.resolve(
                servicesReturning(registration), id -> null);

        assertFalse(bridge.isAvailable());
        assertFalse(bridge.deposit(UUID.randomUUID(), 1.0, UUID.randomUUID()).success());
    }

    @Test
    void lookupExceptionFailsClosed() {
        ServicesManager exploding = (ServicesManager) Proxy.newProxyInstance(
                VaultServiceDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("services broken");
                });

        VaultBridge bridge = VaultServiceDiscovery.resolve(exploding, id -> null);

        assertFalse(bridge.isAvailable());
    }

    @Test
    void offlinePlayerLookupSurvivesToBridge() {
        RegisteredServiceProvider<Economy> registration = new RegisteredServiceProvider<>(
                Economy.class, fakeEconomy(true, "AceEconomy"), ServicePriority.Normal, fakePlugin());
        OfflinePlayer offline = (OfflinePlayer) Proxy.newProxyInstance(
                VaultServiceDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class}, (proxy, method, args) -> null);

        VaultBridge bridge = VaultServiceDiscovery.resolve(
                servicesReturning(registration), id -> offline);

        assertTrue(bridge.isAvailable());
    }
}
