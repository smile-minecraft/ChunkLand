package com.smile.chunkland.limit;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

/**
 * Production cached-metadata read for {@link LuckPermsLimitProvider}.
 *
 * <p>Reads only the already-loaded LuckPerms user: {@code UserManager.getUser}
 * is a cached lookup and {@code getCachedData().getMetaData()} never blocks.
 * It never calls {@code loadUser}, never touches the network, SQL or Bukkit,
 * and every failure degrades to {@link Optional#empty()} so limits fail open
 * to config. This class references the LuckPerms API, which is a
 * {@code compileOnly} boundary (server-provided, never embedded); it is only
 * loaded when discovery already confirmed LuckPerms is present.</p>
 */
final class LuckPermsMetaLookup {

    private LuckPermsMetaLookup() {
    }

    /** Cached-only lookup bound to the LuckPerms singleton. */
    static LuckPermsLimitProvider.MetaLookup cached() {
        LuckPerms api = LuckPermsProvider.get();
        Objects.requireNonNull(api, "LuckPerms api");
        return (playerId, metaKey) -> readCached(api, playerId, metaKey);
    }

    private static Optional<String> readCached(LuckPerms api, UUID playerId, String metaKey) {
        try {
            User user = api.getUserManager().getUser(playerId);
            if (user == null) {
                return Optional.empty();
            }
            QueryOptions options;
            try {
                // Per-user contexts when available, static contexts otherwise;
                // both are in-memory reads, never blocking.
                options = api.getContextManager().getQueryOptions(user)
                        .orElseGet(api.getContextManager()::getStaticQueryOptions);
            } catch (RuntimeException contextsUnavailable) {
                return Optional.empty();
            }
            if (options == null) {
                return Optional.empty();
            }
            CachedMetaData meta;
            try {
                meta = user.getCachedData().getMetaData(options);
            } catch (RuntimeException cacheUnavailable) {
                return Optional.empty();
            }
            if (meta == null) {
                return Optional.empty();
            }
            String value;
            try {
                value = meta.getMetaValue(metaKey);
            } catch (RuntimeException metaUnavailable) {
                return Optional.empty();
            }
            return Optional.ofNullable(value);
        } catch (RuntimeException lookupFailed) {
            return Optional.empty();
        }
    }

    /**
     * Fail-open presence probe: true only when the LuckPerms plugin is enabled
     * and its API singleton answers.
     */
    static boolean available(Supplier<Boolean> pluginPresent) {
        try {
            if (pluginPresent == null || !Boolean.TRUE.equals(pluginPresent.get())) {
                return false;
            }
            LuckPermsProvider.get();
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
}
