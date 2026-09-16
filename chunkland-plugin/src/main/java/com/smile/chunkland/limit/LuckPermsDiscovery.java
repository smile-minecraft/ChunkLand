package com.smile.chunkland.limit;

import com.smile.chunkland.api.limit.ExternalLimitProvider;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Optional discovery for the LuckPerms limit provider.
 *
 * <p>Discovery is presence-gated and fail-open: when LuckPerms is absent, its
 * API is unreachable, or the lookup factory throws, discovery yields
 * {@link Optional#empty()} and callers keep the typed config limits. The
 * LuckPerms API classes are only touched inside the supplied lookup factory
 * (production: {@link LuckPermsMetaLookup}), so merely calling discovery never
 * loads LuckPerms types.</p>
 */
public final class LuckPermsDiscovery {

    private LuckPermsDiscovery() {
    }

    /**
     * @param luckPermsPresent true when the LuckPerms plugin is present/enabled
     *                         (production: Bukkit plugin-manager probe)
     * @param lookupFactory produces the cached-metadata lookup; only invoked
     *                      when present (production: {@link LuckPermsMetaLookup#cached()})
     * @return provider when LuckPerms is usable, otherwise empty
     */
    public static Optional<ExternalLimitProvider> discover(
            Supplier<Boolean> luckPermsPresent,
            Supplier<LuckPermsLimitProvider.MetaLookup> lookupFactory) {
        Objects.requireNonNull(luckPermsPresent, "luckPermsPresent");
        Objects.requireNonNull(lookupFactory, "lookupFactory");
        boolean present;
        try {
            present = Boolean.TRUE.equals(luckPermsPresent.get());
        } catch (RuntimeException probeFailed) {
            return Optional.empty();
        }
        if (!present) {
            return Optional.empty();
        }
        try {
            LuckPermsLimitProvider.MetaLookup lookup = lookupFactory.get();
            if (lookup == null) {
                return Optional.empty();
            }
            return Optional.of(new LuckPermsLimitProvider(lookup));
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Production entry point: presence-gated discovery over the cached
     * LuckPerms metadata lookup. The {@code LuckPermsMetaLookup} reference
     * below resolves only when this method runs, so calling discovery on a
     * server without LuckPerms never loads LuckPerms types.
     *
     * @param luckPermsPresent true when the LuckPerms plugin is present/enabled
     * @return provider when LuckPerms is usable, otherwise empty
     */
    public static Optional<ExternalLimitProvider> discover(
            Supplier<Boolean> luckPermsPresent) {
        return discover(luckPermsPresent, LuckPermsMetaLookup::cached);
    }
}
