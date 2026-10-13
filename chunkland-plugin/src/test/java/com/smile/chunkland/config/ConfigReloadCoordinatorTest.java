package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Conservative-startup rule for the admin reload path.
 *
 * <p>From a normal startup the coordinator is a straight pass-through to
 * the content gate. From a conservative startup a reload may only exit the
 * fallback when the server world set is unchanged since startup; any world
 * drift, any unreadable world list, or any structural content change refuses
 * the whole candidate with a restart prompt. Loader failures never exit the
 * fallback either.</p>
 */
class ConfigReloadCoordinatorTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            """;

    private static final class MutableLoader implements ConfigLoader {
        volatile String yaml = BASE;

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "mutable";
        }
    }

    private static final class FailingLoader implements ConfigLoader {
        @Override
        public ChunkLandConfig load() {
            throw new ConfigValidationException("broken");
        }

        @Override
        public String describe() {
            return "failing";
        }
    }

    private static ConfigReloadCoordinator coordinator(ConfigService service,
                                                       AtomicBoolean conservative,
                                                       AtomicBoolean exited,
                                                       Set<String> currentWorlds,
                                                       boolean worldsReadable) {
        return new ConfigReloadCoordinator(
                service,
                conservative::get,
                () -> {
                    exited.set(true);
                    conservative.set(false);
                },
                () -> {
                    if (!worldsReadable) {
                        throw new RuntimeException("world list unreadable");
                    }
                    return currentWorlds;
                },
                Set.of("world"),
                com.smile.chunkland.api.money.Currency.of("EMC", 2));
    }

    @Test
    void normalStartupPassesThroughToSuccess() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
        AtomicBoolean conservative = new AtomicBoolean(false);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + "messages:\n  default-locale: en_US\n  cooldown-seconds: 7\n";

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertFalse(exited.get(), "a normal startup has no fallback to exit");
        assertEquals(7, service.current().messages().cooldownSeconds());
    }

    @Test
    void conservativeStartupExitsOnValidUnchangedWorlds() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE;

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertTrue(exited.get(), "a valid file with unchanged worlds safely exits the fallback");
        assertFalse(conservative.get());
        assertEquals("world", service.current().worlds().keySet().iterator().next());
    }

    @Test
    void conservativeStartupRefusesWhenWorldsDrifted() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE;

        ReloadOutcome outcome = coordinator(service, conservative, exited,
                Set.of("world", "world_nether"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertNotNull(outcome.reason());
        assertFalse(exited.get(), "a drifted world set must not exit the fallback");
        assertTrue(conservative.get());
        assertSame(conservativeSnapshot, service.current());
    }

    @Test
    void conservativeStartupRefusesWhenWorldsUnreadable() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE;

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), false).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertFalse(exited.get());
        assertSame(conservativeSnapshot, service.current());
    }

    @Test
    void conservativeStartupRefusesWhenCandidateWorldsDrifted() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = """
                worlds:
                  world:
                    claim-enabled: true
                    vertical-mode: PER_CHUNK_DEPTH
                  world_nether:
                    claim-enabled: true
                    vertical-mode: PER_CHUNK_DEPTH
                """;

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertNotNull(outcome.reason());
        assertFalse(exited.get(), "a drifted candidate world set must not exit the fallback");
        assertTrue(conservative.get());
        assertSame(conservativeSnapshot, service.current());
    }

    @Test
    void structuralChangeIsRejectedEvenOutsideConservative() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
        AtomicBoolean conservative = new AtomicBoolean(false);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + "audit:\n  retention-days: 30\n";

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind());
        assertEquals(180, service.current().audit().retentionDays());
    }

    @Test
    void loaderFailureNeverExitsConservative() {
        ConfigService service = new ConfigService(
                new FailingLoader(), ConservativeConfigFactory.forWorlds(java.util.List.of("world")));
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.FAILED, outcome.kind());
        assertNotNull(outcome.cause());
        assertFalse(exited.get());
        assertTrue(conservative.get());
    }

    private static String economyBlock(boolean enabled, String code) {
        return """
                economy:
                  enabled: %s
                  currency:
                    code: %s
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """.formatted(enabled, code);
    }

    @Test
    void conservativeStartupWithEnabledEconomyIsRejected() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + economyBlock(true, "EMC");

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind(),
                "an enabled economy must not exit the fallback: live pricing stays free");
        assertNotNull(outcome.reason());
        assertFalse(exited.get(), "the flag must not flip on a refused candidate");
        assertTrue(conservative.get());
        assertSame(conservativeSnapshot, service.current(),
                "the refused candidate publishes nothing");
    }

    @Test
    void conservativeStartupWithStillDisabledEconomyExits() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + economyBlock(false, "EMC");

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertTrue(exited.get(), "a still-disabled economy matches the assembled footprint");
        assertFalse(conservative.get());
    }

    @Test
    void conservativeStartupWithChangedAuditIsRejected() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + "audit:\n  retention-days: 30\n";

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind(),
                "audit must refuse like the normal path instead of drifting between the two rules");
        assertFalse(exited.get());
        assertTrue(conservative.get());
        assertSame(conservativeSnapshot, service.current());
    }

    @Test
    void normalStartupWithEconomyChangeIsRejected() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
        AtomicBoolean conservative = new AtomicBoolean(false);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + economyBlock(true, "EMC");

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind(),
                "the normal path still refuses any economy change via the content policy");
        assertEquals(180, service.current().audit().retentionDays());
        assertSame(service.current(), outcome.snapshot());
    }

    @Test
    void conservativeStartupWithDifferentCurrencyWhileDisabledIsRejected() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig conservativeSnapshot = ConservativeConfigFactory.forWorlds(java.util.List.of("world"));
        ConfigService service = new ConfigService(loader, conservativeSnapshot);
        AtomicBoolean conservative = new AtomicBoolean(true);
        AtomicBoolean exited = new AtomicBoolean(false);
        loader.yaml = BASE + economyBlock(false, "DIA");

        ReloadOutcome outcome = coordinator(service, conservative, exited, Set.of("world"), true).reload();

        assertEquals(ReloadOutcome.Kind.REJECTED, outcome.kind(),
                "a disabled economy in another currency does not match the assembled zero table");
        assertFalse(exited.get());
        assertTrue(conservative.get());
        assertSame(conservativeSnapshot, service.current());
    }
}
