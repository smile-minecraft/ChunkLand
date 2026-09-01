package com.smile.chunkland.limit;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.ExternalLimitProvider;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.config.ChunkLandConfig;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a limit value for an owner/type, preferring an external provider
 * when present and falling back to {@link ChunkLandConfig#limits()}.
 *
 * <p>Provider absent or returning empty always falls back to config with source CONFIG.
 * Provider result must carry source PROVIDER so callers can distinguish provenance.</p>
 */
public final class LimitResolver {

    private final ChunkLandConfig config;
    private final Optional<ExternalLimitProvider> provider;

    public LimitResolver(ChunkLandConfig config, Optional<ExternalLimitProvider> provider) {
        this.config = Objects.requireNonNull(config, "config");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public LimitResolver(ChunkLandConfig config) {
        this(config, Optional.empty());
    }

    public LimitResult resolve(OwnerRef owner, LimitType type) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        if (provider.isPresent()) {
            Optional<LimitResult> maybe = provider.get().resolve(owner, type);
            if (maybe != null && maybe.isPresent()) {
                LimitResult r = maybe.get();
                if (r.source() != LimitSource.PROVIDER) {
                    throw new IllegalStateException("provider must return source PROVIDER, got " + r.source());
                }
                if (r.limit() < 0) {
                    throw new IllegalStateException("provider returned negative limit: " + r.limit());
                }
                return r;
            }
        }
        long configValue = config.limits().longValueFor(type);
        return LimitResult.of(configValue, LimitSource.CONFIG);
    }
}
