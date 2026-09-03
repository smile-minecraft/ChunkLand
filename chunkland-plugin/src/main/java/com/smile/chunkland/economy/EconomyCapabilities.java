package com.smile.chunkland.economy;

import java.util.Objects;

/**
 * Observable provider capabilities. When Vault or the underlying economy plugin
 * is absent, {@code available} and {@code canCharge} are false and callers
 * must fail closed rather than reporting a successful charge.
 */
public record EconomyCapabilities(
        boolean available,
        boolean canCharge,
        boolean canRefund,
        String providerId,
        String displayName) {

    public EconomyCapabilities {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(displayName, "displayName");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
    }

    public static EconomyCapabilities unavailable() {
        return new EconomyCapabilities(false, false, false, "none", "Unavailable");
    }

    public static EconomyCapabilities available(String providerId, String displayName) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(displayName, "displayName");
        return new EconomyCapabilities(true, true, true, providerId, displayName);
    }

    public static EconomyCapabilities available(String providerId) {
        return available(providerId, providerId);
    }
}
