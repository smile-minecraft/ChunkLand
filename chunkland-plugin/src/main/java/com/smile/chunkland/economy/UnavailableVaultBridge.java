package com.smile.chunkland.economy;

import java.util.UUID;

/**
 * Fail-closed bridge when Vault or the underlying economy plugin is not present.
 * All mutating calls report failure and {@link #isAvailable()} is false so
 * {@link VaultEconomyAdapter#capabilities()} reports not chargeable.
 */
public final class UnavailableVaultBridge implements VaultBridge {

    private final String providerId;

    public UnavailableVaultBridge() {
        this("none");
    }

    public UnavailableVaultBridge(String providerId) {
        this.providerId = providerId == null || providerId.isBlank() ? "none" : providerId;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Response withdraw(UUID playerId, double amount, UUID operationId) {
        return Response.failed("economy.unavailable");
    }

    @Override
    public Response deposit(UUID playerId, double amount, UUID operationId) {
        return Response.failed("economy.unavailable");
    }
}
