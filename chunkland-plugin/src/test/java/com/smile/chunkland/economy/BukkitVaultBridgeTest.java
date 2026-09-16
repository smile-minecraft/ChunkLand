package com.smile.chunkland.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

/**
 * Red: the production Vault Legacy bridge must map {@code withdrawPlayer} /
 * {@code depositPlayer} responses, stay fail-closed on every defect, and never
 * call the provider for invalid input.
 */
class BukkitVaultBridgeTest {

    private static final class EconomyFake implements InvocationHandler {
        boolean enabled = true;
        String name = "AceEconomy";
        EconomyResponse withdrawResponse = ok(100.0, 900.0);
        EconomyResponse depositResponse = ok(100.0, 1100.0);
        RuntimeException withdrawFailure;
        RuntimeException depositFailure;
        int withdrawCalls;
        int depositCalls;
        double lastWithdrawAmount;
        double lastDepositAmount;
        OfflinePlayer lastWithdrawPlayer;
        boolean enabledThrows;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "isEnabled" -> {
                    if (enabledThrows) {
                        throw new IllegalStateException("provider broken");
                    }
                    yield enabled;
                }
                case "getName" -> name;
                case "withdrawPlayer" -> {
                    if (args != null && args.length == 2 && args[0] instanceof OfflinePlayer) {
                        withdrawCalls++;
                        lastWithdrawPlayer = (OfflinePlayer) args[0];
                        lastWithdrawAmount = (Double) args[1];
                        if (withdrawFailure != null) {
                            throw withdrawFailure;
                        }
                        yield withdrawResponse;
                    }
                    yield defaultValue(method.getReturnType());
                }
                case "depositPlayer" -> {
                    if (args != null && args.length == 2 && args[0] instanceof OfflinePlayer) {
                        depositCalls++;
                        lastDepositAmount = (Double) args[1];
                        if (depositFailure != null) {
                            throw depositFailure;
                        }
                        yield depositResponse;
                    }
                    yield defaultValue(method.getReturnType());
                }
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static Economy economy(EconomyFake fake) {
        return (Economy) Proxy.newProxyInstance(BukkitVaultBridgeTest.class.getClassLoader(),
                new Class<?>[] {Economy.class}, fake);
    }

    private static OfflinePlayer player(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(BukkitVaultBridgeTest.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return id;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static EconomyResponse ok(double amount, double balance) {
        return new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }

    @Test
    void liveProviderIsAvailableWithStableIdentity() {
        EconomyFake fake = new EconomyFake();
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertTrue(bridge.isAvailable());
        assertEquals("vault:AceEconomy", bridge.providerId());
    }

    @Test
    void withdrawSuccessMapsAmountAndPlayer() {
        EconomyFake fake = new EconomyFake();
        UUID id = UUID.randomUUID();
        OfflinePlayer offline = player(id);
        BukkitVaultBridge bridge = new BukkitVaultBridge(economy(fake), ignored -> offline);

        VaultBridge.Response response = bridge.withdraw(id, 100.0, UUID.randomUUID());

        assertTrue(response.success());
        assertEquals(1, fake.withdrawCalls);
        assertEquals(100.0, fake.lastWithdrawAmount);
        assertTrue(fake.lastWithdrawPlayer == offline);
    }

    @Test
    void withdrawInsufficientMapsToInsufficientKey() {
        EconomyFake fake = new EconomyFake();
        fake.withdrawResponse =
                new EconomyResponse(0, 10.0, ResponseType.FAILURE, "Insufficient funds");
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        VaultBridge.Response response = bridge.withdraw(UUID.randomUUID(), 100.0, UUID.randomUUID());

        assertFalse(response.success());
        assertEquals("economy.insufficient", response.errorKey());
    }

    @Test
    void withdrawFailureMapsToFailedKey() {
        EconomyFake fake = new EconomyFake();
        fake.withdrawResponse = new EconomyResponse(0, 0, ResponseType.FAILURE, "storage blew up");
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        VaultBridge.Response response = bridge.withdraw(UUID.randomUUID(), 100.0, UUID.randomUUID());

        assertFalse(response.success());
        assertEquals("economy.failed", response.errorKey());
    }

    @Test
    void withdrawProviderExceptionPropagatesForAdapterMapping() {
        EconomyFake fake = new EconomyFake();
        fake.withdrawFailure = new IllegalStateException("vault backend down");
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertThrows(RuntimeException.class,
                () -> bridge.withdraw(UUID.randomUUID(), 10.0, UUID.randomUUID()));
    }

    @Test
    void depositSuccessMapsAmount() {
        EconomyFake fake = new EconomyFake();
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        VaultBridge.Response response = bridge.deposit(UUID.randomUUID(), 25.5, UUID.randomUUID());

        assertTrue(response.success());
        assertEquals(1, fake.depositCalls);
        assertEquals(25.5, fake.lastDepositAmount);
    }

    @Test
    void depositProviderExceptionPropagatesSoRecoveryRetriesAsUnknown() {
        EconomyFake fake = new EconomyFake();
        fake.depositFailure = new IllegalStateException("vault backend down");
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertThrows(RuntimeException.class,
                () -> bridge.deposit(UUID.randomUUID(), 10.0, UUID.randomUUID()));
    }

    @Test
    void nullProviderResponseThrowsInsteadOfMisreporting() {
        EconomyFake fake = new EconomyFake();
        fake.withdrawResponse = null;
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertThrows(RuntimeException.class,
                () -> bridge.withdraw(UUID.randomUUID(), 10.0, UUID.randomUUID()));
    }

    @Test
    void invalidAmountsNeverReachTheProvider() {
        EconomyFake fake = new EconomyFake();
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertFalse(bridge.withdraw(UUID.randomUUID(), Double.NaN, UUID.randomUUID()).success());
        assertFalse(bridge.withdraw(UUID.randomUUID(), -1.0, UUID.randomUUID()).success());
        assertFalse(bridge.deposit(UUID.randomUUID(), Double.POSITIVE_INFINITY, UUID.randomUUID())
                .success());
        assertEquals(0, fake.withdrawCalls);
        assertEquals(0, fake.depositCalls);
    }

    @Test
    void unknownPlayerLookupFailsClosed() {
        EconomyFake fake = new EconomyFake();
        BukkitVaultBridge bridge = new BukkitVaultBridge(economy(fake), ignored -> null);

        assertFalse(bridge.withdraw(UUID.randomUUID(), 10.0, UUID.randomUUID()).success());
        assertEquals(0, fake.withdrawCalls);
    }

    @Test
    void disabledProviderIsUnavailableAndNeverCharged() {
        EconomyFake fake = new EconomyFake();
        fake.enabled = false;
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertFalse(bridge.isAvailable());
        assertFalse(bridge.withdraw(UUID.randomUUID(), 10.0, UUID.randomUUID()).success());
        assertEquals(0, fake.withdrawCalls);
    }

    @Test
    void throwingAvailabilityCheckFailsClosed() {
        EconomyFake fake = new EconomyFake();
        fake.enabledThrows = true;
        BukkitVaultBridge bridge =
                new BukkitVaultBridge(economy(fake), id -> player(id));

        assertFalse(bridge.isAvailable());
    }
}
