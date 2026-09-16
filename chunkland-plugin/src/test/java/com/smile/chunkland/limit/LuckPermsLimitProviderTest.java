package com.smile.chunkland.limit;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.limit.LimitResult;
import com.smile.chunkland.api.limit.LimitSource;
import com.smile.chunkland.api.limit.LimitType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Red contract for the optional LuckPerms external limit provider.
 *
 * <p>Metadata convention: {@code chunkland.limit.<LimitType.configKey()>}, trimmed
 * non-negative base-10 long. Blank, negative, overflowing, non-numeric and
 * throwing lookups all fail open to the typed config value.
 */
class LuckPermsLimitProviderTest {

    private static ChunkLandConfig configWithDefaults() {
        return new ChunkLandConfig(Map.of(), LimitSettings.defaults(), 0L, Map.of());
    }

    private static OwnerRef player() {
        return OwnerRef.player(UUID.randomUUID());
    }

    private static LuckPermsLimitProvider.MetaLookup fixed(String value) {
        return (uuid, key) -> Optional.ofNullable(value);
    }

    @Test
    void metaKeyUsesConfigKeyConvention() {
        for (LimitType type : LimitType.values()) {
            assertEquals("chunkland.limit." + type.configKey(),
                    LuckPermsLimitProvider.metaKey(type));
        }
    }

    @Test
    void validMetaValueUsesProviderLimit() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider(fixed("10"));
        LimitResolver resolver = new LimitResolver(configWithDefaults(),
                Optional.of(provider));
        LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(10L, result.limit());
        assertEquals(LimitSource.PROVIDER, result.source());
    }

    @Test
    void everySupportedPlayerLimitReadsItsOwnKey() {
        for (LimitType type : LimitType.values()) {
            UUID uuid = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(uuid);
            String expectedKey = "chunkland.limit." + type.configKey();
            String[] seenKey = new String[1];
            LuckPermsLimitProvider provider = new LuckPermsLimitProvider((id, key) -> {
                seenKey[0] = key;
                return Optional.of("7");
            });
            Optional<LimitResult> result = provider.resolve(owner, type);
            assertTrue(result.isPresent(), "supported player limit must resolve: " + type);
            assertEquals(expectedKey, seenKey[0]);
            assertEquals(7L, result.get().limit());
            assertEquals(LimitSource.PROVIDER, result.get().source());
        }
    }

    @Test
    void missingUserFallsBackToConfig() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider =
                new LuckPermsLimitProvider((uuid, key) -> Optional.empty());
        LimitResolver resolver = new LimitResolver(configWithDefaults(),
                Optional.of(provider));
        LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitType.MAX_LANDS_PER_PLAYER.defaultValue(), result.limit());
        assertEquals(LimitSource.CONFIG, result.source());
    }

    @Test
    void invalidMetaValuesFallBackToConfig() {
        String[] invalid = {"", "   ", "abc", "12.5", "-3", "0x10", "10 lands",
                "9999999999999999999999", "9223372036854775808"};
        for (String raw : invalid) {
            OwnerRef owner = player();
            LuckPermsLimitProvider provider = new LuckPermsLimitProvider(fixed(raw));
            assertTrue(provider.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER).isEmpty(),
                    "invalid meta must be empty: '" + raw + "'");
            LimitResolver resolver = new LimitResolver(configWithDefaults(),
                    Optional.of(provider));
            LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
            assertEquals(LimitSource.CONFIG, result.source(), "raw: '" + raw + "'");
        }
    }

    @Test
    void zeroIsAValidProviderLimit() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider(fixed("0"));
        Optional<LimitResult> result =
                provider.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertTrue(result.isPresent());
        assertEquals(0L, result.get().limit());
        assertEquals(LimitSource.PROVIDER, result.get().source());
    }

    @Test
    void whitespaceIsTrimmed() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider =
                new LuckPermsLimitProvider(fixed("  42  "));
        Optional<LimitResult> result =
                provider.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertTrue(result.isPresent());
        assertEquals(42L, result.get().limit());
    }

    @Test
    void throwingLookupFailsOpenToConfig() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider((uuid, key) -> {
            throw new IllegalStateException("luckperms unavailable");
        });
        assertTrue(provider.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER).isEmpty());
        LimitResolver resolver = new LimitResolver(configWithDefaults(),
                Optional.of(provider));
        LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitSource.CONFIG, result.source());
    }

    @Test
    void serverOwnerNeverConsultsProvider() {
        AtomicInteger calls = new AtomicInteger(0);
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider((uuid, key) -> {
            calls.incrementAndGet();
            return Optional.of("99");
        });
        assertTrue(provider.resolve(OwnerRef.server(), LimitType.MAX_LANDS_PER_PLAYER).isEmpty());
        assertEquals(0, calls.get(), "server owner must not touch the provider");
    }

    @Test
    void resolveUsesSingleCachedLookupWithoutBlockingLoad() {
        AtomicInteger lookups = new AtomicInteger(0);
        AtomicInteger blockingLoads = new AtomicInteger(0);
        UUID uuid = UUID.randomUUID();
        OwnerRef owner = OwnerRef.player(uuid);
        // The provider only receives the cached-metadata lookup; the blocking
        // loader stub is never handed to it, so it cannot call loadUser.
        LuckPermsLimitProvider.MetaLookup cached = (id, key) -> {
            lookups.incrementAndGet();
            assertEquals(uuid, id);
            assertEquals("chunkland.limit.max-lands-per-player", key);
            return Optional.of("9");
        };
        Supplier<Optional<String>> blockingLoader = () -> {
            blockingLoads.incrementAndGet();
            return Optional.of("9");
        };
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider(cached);
        Optional<LimitResult> result =
                provider.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertTrue(result.isPresent());
        assertEquals(9L, result.get().limit());
        assertEquals(1, lookups.get(), "exactly one cached lookup per resolve");
        assertEquals(0, blockingLoads.get(), "blocking loader must never run");
        assertNotNull(blockingLoader, "kept referenced so the test seam is explicit");
    }

    @Test
    void providerQuotaFlowsIntoClaimReservation() {
        OwnerRef owner = player();
        LuckPermsLimitProvider provider = new LuckPermsLimitProvider(fixed("1"));
        OwnerQuotaService quotas = new OwnerQuotaService(new LimitResolver(
                configWithDefaults(), Optional.of(provider)));
        assertTrue(quotas.tryReserveLand(owner).isPresent(), "first land within provider limit");
        assertTrue(quotas.tryReserveLand(owner).isEmpty(), "provider limit 1 must reject second");
    }

    @Test
    void discoveryWithoutLuckPermsStaysEmpty() {
        assertTrue(LuckPermsDiscovery.discover(() -> false, () -> fixed("10")).isEmpty());
        assertTrue(LuckPermsDiscovery.discover(() -> false, () -> {
            throw new AssertionError("factory must not run when absent");
        }).isEmpty());
    }

    @Test
    void discoveryWithFailingProbeOrFactoryStaysEmpty() {
        assertTrue(LuckPermsDiscovery.discover(() -> {
            throw new IllegalStateException("no server");
        }, () -> fixed("10")).isEmpty());
        assertTrue(LuckPermsDiscovery.discover(() -> true, () -> {
            throw new IllegalStateException("api not ready");
        }).isEmpty());
        assertTrue(LuckPermsDiscovery.discover(() -> true, () -> null).isEmpty());
    }

    @Test
    void discoveryWithLuckPermsProvidesLookup() {
        OwnerRef owner = player();
        Optional<com.smile.chunkland.api.limit.ExternalLimitProvider> discovered =
                LuckPermsDiscovery.discover(() -> true, () -> fixed("10"));
        assertTrue(discovered.isPresent());
        assertEquals(10L, discovered.get().resolve(owner, LimitType.MAX_LANDS_PER_PLAYER)
                .orElseThrow().limit());
    }

    @Test
    void absentProviderKeepsConfigBehaviour() {
        OwnerRef owner = player();
        LimitResolver resolver = new LimitResolver(configWithDefaults());
        LimitResult result = resolver.resolve(owner, LimitType.MAX_LANDS_PER_PLAYER);
        assertEquals(LimitType.MAX_LANDS_PER_PLAYER.defaultValue(), result.limit());
        assertEquals(LimitSource.CONFIG, result.source());
    }

}
