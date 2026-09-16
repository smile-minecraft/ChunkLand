package com.smile.chunkland.economy;

import java.util.UUID;
import java.util.function.Function;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * Resolves the Vault Legacy {@code Economy} provider from the Bukkit
 * {@link ServicesManager}.
 *
 * <p>Only the Legacy namespace ({@code net.milkbowl.vault.economy.Economy})
 * is consulted: that is the namespace the live provider registers, and the
 * only namespace {@link BukkitVaultBridge} knows how to drive. A missing
 * plugin, a missing registration, a null or disabled provider, or any lookup
 * failure resolves to the explicitly unavailable bridge — ChunkLand keeps
 * running with claims failing closed, never half-wired and never free.
 * A discovered-but-disabled provider keeps its {@code vault:<name>}
 * identity on the unavailable bridge so diagnostics still name the provider
 * that refused to serve.
 */
public final class VaultServiceDiscovery {

    private VaultServiceDiscovery() {}

    /**
     * Resolve the live bridge, or an explicitly unavailable bridge when Vault
     * is absent, has no registration, or cannot serve.
     *
     * @param services Bukkit services manager; null fails closed
     * @param players offline-player lookup for later charge/refund calls;
     *                null fails closed since the bridge could never serve
     */
    public static VaultBridge resolve(ServicesManager services, Function<UUID, OfflinePlayer> players) {
        if (services == null || players == null) {
            return new UnavailableVaultBridge();
        }
        try {
            RegisteredServiceProvider<Economy> registration =
                    services.getRegistration(Economy.class);
            if (registration == null) {
                return new UnavailableVaultBridge();
            }
            Economy provider = registration.getProvider();
            if (provider == null) {
                return new UnavailableVaultBridge();
            }
            BukkitVaultBridge bridge = new BukkitVaultBridge(provider, players);
            if (!bridge.isAvailable()) {
                return new UnavailableVaultBridge(bridge.providerId());
            }
            return bridge;
        } catch (LinkageError | RuntimeException e) {
            return new UnavailableVaultBridge();
        }
    }
}
