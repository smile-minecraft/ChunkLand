package com.smile.chunkland.economy;

import java.util.UUID;

/**
 * Boundary interface over the external Vault {@code Economy}. This is the
 * only place allowed to touch Vault types at runtime; the rest of the codebase
 * works in {@link com.smile.chunkland.api.money.Money} and
 * {@link com.smile.chunkland.runtime.mutation.EconomyOperator}.
 *
 * <p>Implementations are responsible for resolving the server's Economy provider
 * (e.g. via {@code net.milkbowl.vault.economy.Economy}) and translating the
 * Vault {@code EconomyResponse} into {@link Response}. This interface must not
 * leak Vault API types and must remain in {@code chunkland-plugin} only.
 */
public interface VaultBridge {

    /** Whether a backing economy provider is present and usable. */
    boolean isAvailable();

    /** Stable provider identity for ledger payload (e.g. "vault:EssentialsX"). */
    String providerId();

    /** Withdraw {@code amount} major units from the player identified by {@code playerId}. */
    Response withdraw(UUID playerId, double amount, UUID operationId);

    /** Deposit {@code amount} major units to the player identified by {@code playerId}. */
    Response deposit(UUID playerId, double amount, UUID operationId);

    /** Minimal, Vault-agnostic result. */
    record Response(boolean success, String errorKey) {
        public static Response ok() {
            return new Response(true, null);
        }

        public static Response failed(String errorKey) {
            java.util.Objects.requireNonNull(errorKey, "errorKey");
            return new Response(false, errorKey);
        }
    }
}
