package com.smile.chunkland.api.limit;

import com.smile.chunkland.api.land.OwnerRef;
import java.util.Optional;

/**
 * External limit provider contract.
 *
 * <p>Implementations are optional and loaded externally; the LuckPerms metadata
 * lookup is the one shipped in this plugin. When no provider is present, or when
 * the provider returns {@link Optional#empty()},
 * the caller must fall back to the typed {@code config.yml} limit. The returned
 * {@link LimitResult#source()} must be {@link LimitSource#PROVIDER} when a provider
 * supplies a value, so callers can distinguish provider-supplied limits from
 * config defaults for future {@code /land inspect} style diagnostics.</p>
 *
 * <p>Implementations must be thread-safe, must not throw for unknown
 * {@link LimitType} values (return empty instead), and must not allocate
 * excessively on the hot path. The interface intentionally does not reference
 * Bukkit, SQL or AceLib so {@code chunkland-api} remains dependency-free.</p>
 */
public interface ExternalLimitProvider {

    /**
     * Resolve a limit for the given owner and type.
     *
     * @param owner owner to resolve for (never null; {@link OwnerRef#server()} is permitted)
     * @param type  limit kind (never null)
     * @return provider-supplied result with source PROVIDER, or empty if the provider has no opinion
     */
    Optional<LimitResult> resolve(OwnerRef owner, LimitType type);
}
