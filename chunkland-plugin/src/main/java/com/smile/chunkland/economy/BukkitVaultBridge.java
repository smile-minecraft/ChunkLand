package com.smile.chunkland.economy;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Vault Legacy {@code Economy} boundary: the only class allowed to touch
 * Vault API types at runtime.
 *
 * <p>Provider model: the Legacy {@code Economy} service registered with the
 * Bukkit {@code ServicesManager} (on the live server this is AceEconomy).
 * The Modern {@code vault2} Economy API is deliberately not consumed here:
 * no Modern provider is registered on the server, and mixing the two
 * registration namespaces would risk charging against a different backing
 * store than refunds read. The stable ledger identity is
 * {@code vault:<provider name>}.
 *
 * <p>Failure contract, coordinated with {@link VaultEconomyAdapter}:
 * <ul>
 *   <li>Absent, disabled, or unreadable provider: {@link #isAvailable()}
 *       returns false and every mutating call reports {@code economy.unavailable}
 *       without touching the provider.</li>
 *   <li>Invalid input (null ids, non-finite or negative amount, unknown
 *       player): fail-closed {@code economy.failed}, provider never called.</li>
 *   <li>Explicit Vault {@code FAILURE}/{@code NOT_IMPLEMENTED} response:
 *       mapped to {@code economy.insufficient} (funds-shaped message),
 *       {@code economy.unsupported}, or {@code economy.failed}. A returned
 *       failure stays non-retryable at the adapter layer.</li>
 *   <li>Provider exception or {@code null} response: propagated, never
 *       converted to success or to a returned failure. The adapter maps a
 *       thrown withdraw to {@code economy.failed} and a thrown deposit to
 *       {@code UNKNOWN}, so recovery keeps retrying instead of dropping or
 *       double-booking money.</li>
 * </ul>
 *
 * <p>Threading: the Legacy provider's thread-safety is unverified on the
 * live Folia build, so callers must keep every call on the async claim
 * executor the saga already uses — never on a region thread and never
 * inside a SQL transaction. The Legacy API has no provider-side idempotency
 * key: the {@code operationId} is accepted by the bridge contract but
 * discarded here, because Vault {@code depositPlayer}/{@code withdrawPlayer}
 * take no idempotency parameter and the provider can never dedup. Cross-
 * restart exactly-once therefore rests solely on the saga ledger (park the
 * execution intent before depositing, never resend a parked row); the
 * adapter's in-process dedup only coalesces concurrent callers and is not a
 * restart guarantee.
 */
public final class BukkitVaultBridge implements VaultBridge {

    private final Economy economy;
    private final Function<UUID, OfflinePlayer> players;
    private final String providerId;

    public BukkitVaultBridge(Economy economy, Function<UUID, OfflinePlayer> players) {
        this.economy = Objects.requireNonNull(economy, "economy");
        this.players = Objects.requireNonNull(players, "players");
        this.providerId = "vault:" + readProviderName(economy);
    }

    private static String readProviderName(Economy economy) {
        try {
            String name = economy.getName();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall through to the unknown identity; availability still
            // reports through isEnabled() below.
        }
        return "unknown";
    }

    @Override
    public boolean isAvailable() {
        try {
            return economy.isEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Response withdraw(UUID playerId, double amount, UUID operationId) {
        String invalid = invalidCall(playerId, amount, operationId);
        if (invalid != null) {
            return Response.failed(invalid);
        }
        if (!isAvailable()) {
            return Response.failed("economy.unavailable");
        }
        OfflinePlayer offline = lookup(playerId);
        if (offline == null) {
            return Response.failed("economy.failed");
        }
        EconomyResponse response = economy.withdrawPlayer(offline, amount);
        if (response == null) {
            throw new IllegalStateException("vault provider returned null withdraw response");
        }
        return map(response);
    }

    @Override
    public Response deposit(UUID playerId, double amount, UUID operationId) {
        String invalid = invalidCall(playerId, amount, operationId);
        if (invalid != null) {
            return Response.failed(invalid);
        }
        if (!isAvailable()) {
            return Response.failed("economy.unavailable");
        }
        OfflinePlayer offline = lookup(playerId);
        if (offline == null) {
            return Response.failed("economy.failed");
        }
        EconomyResponse response = economy.depositPlayer(offline, amount);
        if (response == null) {
            throw new IllegalStateException("vault provider returned null deposit response");
        }
        return map(response);
    }

    private OfflinePlayer lookup(UUID playerId) {
        try {
            return players.apply(playerId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String invalidCall(UUID playerId, double amount, UUID operationId) {
        if (playerId == null || operationId == null) {
            return "economy.failed";
        }
        if (!Double.isFinite(amount) || amount < 0.0) {
            return "economy.failed";
        }
        return null;
    }

    private static Response map(EconomyResponse response) {
        if (response.transactionSuccess()) {
            return Response.ok();
        }
        if (response.type == EconomyResponse.ResponseType.NOT_IMPLEMENTED) {
            return Response.failed("economy.unsupported");
        }
        if (isInsufficient(response.errorMessage)) {
            return Response.failed("economy.insufficient");
        }
        return Response.failed("economy.failed");
    }

    private static boolean isInsufficient(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("insuffic") || lower.contains("not enough") || lower.contains("funds");
    }
}
