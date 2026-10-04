package com.smile.chunkland.claim;

import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.WorldSettings;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Per-world {@code claim-enabled} gate for new player claims.
 *
 * <p>The policy reads the live {@link ChunkLandConfig} snapshot on every call
 * (one volatile read, no SQL, no Bukkit), so a config reload applies without
 * rebuilding the validator or the saga. Worlds absent from the config follow
 * the enabled default; only an explicit {@code claim-enabled: false} rejects.
 * The strict overload ({@code denyUnlisted}) instead rejects absent worlds:
 * it is used only while the conservative startup fallback is active, so a
 * world the damaged config never listed cannot accept new claims.
 *
 * <p>Fail-closed: a {@code null} world id, an unresolvable or throwing
 * UUID-to-name mapping, or an unreadable snapshot rejects with
 * {@code world.unknown} instead of treating the unknown as enabled. A
 * disabled world — or, under the strict overload, an unlisted one — rejects
 * with {@code world.claim_disabled}.
 *
 * <p>The gate only answers whether a new claim may start; it never deletes,
 * hides or re-decides existing lands, and it never reaches the protection
 * engine's wilderness path.
 */
@FunctionalInterface
public interface WorldClaimPolicy {

    /**
     * Reject the claim when the world policy forbids it.
     *
     * @throws ClaimRejectedException with {@code world.claim_disabled} or
     *         {@code world.unknown} when no new claim may start here
     */
    void checkClaimAllowed(UUID worldId) throws ClaimRejectedException;

    /** Permissive policy for tests and legacy paths that carry no world config. */
    static WorldClaimPolicy allowAll() {
        return worldId -> {
        };
    }

    /** Deny-everything policy for unwired production inputs (fail-closed). */
    static WorldClaimPolicy denyAll(String diagnosticKey) {
        Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        return worldId -> {
            throw new ClaimRejectedException(diagnosticKey);
        };
    }

    /**
     * Production policy over the live config snapshot. Worlds absent from the
     * config follow the enabled default.
     *
     * @param configs live snapshot source (typically {@code ConfigService::current});
     *        a throwing or {@code null} snapshot fails closed
     * @param worldNames UUID-to-config-name mapping; an empty, {@code null} or
     *        throwing result fails closed
     */
    static WorldClaimPolicy fromConfig(Supplier<ChunkLandConfig> configs,
            Function<UUID, Optional<String>> worldNames) {
        return fromConfig(configs, worldNames, false);
    }

    /**
     * Production policy over the live config snapshot, with an explicit
     * choice for worlds absent from the config.
     *
     * @param configs live snapshot source (typically {@code ConfigService::current});
     *        a throwing or {@code null} snapshot fails closed
     * @param worldNames UUID-to-config-name mapping; an empty, {@code null} or
     *        throwing result fails closed
     * @param denyUnlisted when true, a world with no config entry rejects with
     *        {@code world.claim_disabled} instead of following the enabled
     *        default; used only while the conservative startup fallback is
     *        active
     */
    static WorldClaimPolicy fromConfig(Supplier<ChunkLandConfig> configs,
            Function<UUID, Optional<String>> worldNames, boolean denyUnlisted) {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(worldNames, "worldNames");
        return worldId -> {
            if (worldId == null) {
                throw new ClaimRejectedException("world.unknown");
            }
            Optional<String> name;
            try {
                name = worldNames.apply(worldId);
            } catch (RuntimeException failure) {
                throw new ClaimRejectedException("world.unknown");
            }
            if (name == null || name.isEmpty()) {
                throw new ClaimRejectedException("world.unknown");
            }
            ChunkLandConfig config;
            try {
                config = configs.get();
            } catch (RuntimeException failure) {
                throw new ClaimRejectedException("world.unknown");
            }
            if (config == null) {
                throw new ClaimRejectedException("world.unknown");
            }
            Map<String, WorldSettings> worlds;
            try {
                worlds = config.worlds();
            } catch (RuntimeException failure) {
                throw new ClaimRejectedException("world.unknown");
            }
            WorldSettings settings = worlds == null ? null : worlds.get(name.get());
            if (settings == null) {
                if (denyUnlisted) {
                    throw new ClaimRejectedException("world.claim_disabled");
                }
                return;
            }
            if (!settings.claimEnabled()) {
                throw new ClaimRejectedException("world.claim_disabled");
            }
        };
    }
}
