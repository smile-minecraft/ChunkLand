package com.smile.chunkland.limit;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.ExternalLimitProvider;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Optional LuckPerms-backed {@link ExternalLimitProvider}.
 *
 * <p>Metadata convention: {@code chunkland.limit.<LimitType.configKey()>}, for
 * example {@code chunkland.limit.max-lands-per-player}. Only a trimmed,
 * non-negative base-10 long is accepted; blank, negative, overflowing or
 * non-numeric values — and any lookup failure — yield {@link Optional#empty()}
 * so the {@link LimitResolver} falls back to the typed {@code config.yml}
 * value (fail open to config, never to zero).</p>
 *
 * <p>Only player owners are answered; {@link OwnerRef.ServerOwnerRef} always
 * yields empty without touching the lookup. The provider performs exactly one
 * cached-metadata read per {@link #resolve} call and never triggers a blocking
 * user load, network or SQL access: the injected {@link MetaLookup} is
 * expected to read only the already-loaded LuckPerms cached user (production:
 * {@code UserManager.getUser(uuid)} + {@code getCachedData().getMetaData()}).
 * It carries no LuckPerms, Bukkit or SQL types so {@code chunkland-api} stays
 * dependency-free and unit tests run without LuckPerms on the classpath.</p>
 */
public final class LuckPermsLimitProvider implements ExternalLimitProvider {

    /** Metadata key prefix; the full key is this prefix plus {@link LimitType#configKey()}. */
    public static final String META_PREFIX = "chunkland.limit.";

    /**
     * Cached-metadata read seam. Production reads the already-loaded LuckPerms
     * user only; tests and discovery supply fakes. Must never block.
     */
    @FunctionalInterface
    public interface MetaLookup {
        /**
         * @param playerId player UUID (never null)
         * @param metaKey full metadata key (never null)
         * @return raw metadata value, or empty when the user or key is absent
         */
        Optional<String> meta(UUID playerId, String metaKey);
    }

    private final MetaLookup lookup;

    public LuckPermsLimitProvider(MetaLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** Full metadata key for a limit type. */
    public static String metaKey(LimitType type) {
        Objects.requireNonNull(type, "type");
        return META_PREFIX + type.configKey();
    }

    /**
     * Parse a raw metadata value: trim, then accept only non-negative base-10
     * longs. Blank, negative, overflowing and non-numeric inputs yield empty.
     */
    public static OptionalLong parseMetaValue(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return OptionalLong.empty();
        }
        if (!trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(trimmed));
        } catch (NumberFormatException overflow) {
            return OptionalLong.empty();
        }
    }

    @Override
    public Optional<LimitResult> resolve(OwnerRef owner, LimitType type) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        if (!(owner instanceof OwnerRef.PlayerOwnerRef player) || player.uuid() == null) {
            return Optional.empty();
        }
        Optional<String> raw;
        try {
            raw = lookup.meta(player.uuid(), metaKey(type));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (raw == null) {
            return Optional.empty();
        }
        OptionalLong parsed;
        try {
            parsed = raw.map(LuckPermsLimitProvider::parseMetaValue)
                    .orElse(OptionalLong.empty());
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(LimitResult.of(parsed.getAsLong(), LimitSource.PROVIDER));
    }
}
