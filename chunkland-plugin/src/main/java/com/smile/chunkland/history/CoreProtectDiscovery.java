package com.smile.chunkland.history;

import com.smile.chunkland.api.history.HistoryQuery;
import com.smile.chunkland.api.history.WorldHistoryProvider;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Server;

/**
 * Optional discovery for the CoreProtect-backed history provider.
 *
 * <p>Discovery is presence-gated and fail-closed: when the backend plugin is
 * absent, its handle is unreachable, or the lookup factory throws, discovery
 * yields {@link Optional#empty()} and history callers keep the generic
 * unavailable reply. Optional backend types are only touched inside the
 * supplied factories (production: the reflective handle plus
 * {@link CoreProtectLookup}), so merely calling discovery never loads them.
 * A null executor yields empty as well: the provider contract requires an
 * injected executor and never runs a backend lookup on the calling thread.
 */
public final class CoreProtectDiscovery {

    private CoreProtectDiscovery() {
    }

    /**
     * @param present true when the backend plugin is present/enabled
     *                (production: Bukkit plugin-manager probe)
     * @param executor background executor for backend lookups; null yields empty
     * @param lookupFactory produces the backend lookup; only invoked when present
     * @return provider when the backend is usable, otherwise empty
     */
    public static Optional<WorldHistoryProvider> discover(
            Supplier<Boolean> present,
            Executor executor,
            Supplier<CoreProtectHistoryProvider.Lookup> lookupFactory) {
        Objects.requireNonNull(present, "present");
        Objects.requireNonNull(lookupFactory, "lookupFactory");
        if (executor == null) {
            return Optional.empty();
        }
        boolean available;
        try {
            available = Boolean.TRUE.equals(present.get());
        } catch (RuntimeException probeFailed) {
            return Optional.empty();
        }
        if (!available) {
            return Optional.empty();
        }
        try {
            CoreProtectHistoryProvider.Lookup lookup = lookupFactory.get();
            if (lookup == null) {
                return Optional.empty();
            }
            return Optional.of(new CoreProtectHistoryProvider(executor, lookup));
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Production entry point: presence-gated discovery over the reflective
     * backend handle. The handle resolves once inside the lookup factory, so
     * a broken handle fails fast at discovery and every per-query failure
     * later degrades to unavailable inside the provider.
     *
     * @param present true when the backend plugin is present/enabled
     * @param executor background executor for backend lookups; null yields empty
     * @param apiHandle resolves the backend API handle (reflective, may throw)
     * @param server Bukkit server for the per-query world-name read
     * @return provider when the backend is usable, otherwise empty
     */
    public static Optional<WorldHistoryProvider> discover(
            Supplier<Boolean> present,
            Executor executor,
            Supplier<Object> apiHandle,
            Server server) {
        Objects.requireNonNull(apiHandle, "apiHandle");
        return discover(present, executor, () -> {
            Object api = apiHandle.get();
            if (api == null || server == null) {
                throw new IllegalStateException("backend lookup unavailable");
            }
            return (HistoryQuery query) -> CoreProtectLookup.fetch(api, server, query);
        });
    }
}
