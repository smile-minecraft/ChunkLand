package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Prepare-then-publish: a prepare failure fails the whole reload with
 * nothing published and no listener notified, so no mixed generation can
 * ever be observed; a validated prepare publishes exactly the prepared
 * generation.
 *
 * <p>The failure half uses a throwing preparer because the production
 * preparer (the permission view build over validated, immutable input)
 * has no failing input by construction — the seam exists so that a
 * future fallible derivation, or a throwing lookup, fails closed instead
 * of publishing half a generation.</p>
 */
class ReloadPrepareFailureTest {

    private static final String BASE = """
            worlds:
              world:
                claim-enabled: true
                vertical-mode: PER_CHUNK_DEPTH
            subject-defaults:
              global:
                BLOCK_BREAK: DENY
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

    @Test
    void prepareFailurePublishesNothingAndNotifiesNobody() {
        MutableLoader loader = new MutableLoader();
        ChunkLandConfig before = ConfigSchema.parseYamlText(BASE);
        ConfigService service = new ConfigService(loader, before);
        PermissionDefaultsCache cache = new PermissionDefaultsCache(
                service::current, name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);
        service.addPreparer(cache::prepareFor);
        service.addPreparer(loaded -> {
            throw new RuntimeException("derivation boom");
        });
        List<ReloadDiff> heard = new ArrayList<>();
        service.addListener(heard::add);
        loader.yaml = BASE.replace("BLOCK_BREAK: DENY", "BLOCK_BREAK: ALLOW");

        ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

        assertEquals(ReloadOutcome.Kind.FAILED, outcome.kind(),
                "a prepare failure is a failure, not a silent keep");
        assertNotNull(outcome.cause());
        assertTrue(String.valueOf(outcome.cause().getMessage()).contains("boom"));
        assertSame(before, service.current(), "nothing publishes on a prepare failure");
        assertEquals(0L, service.current().globalPolicyEpoch());
        assertTrue(heard.isEmpty(), "no listener observes a half-published generation");
        assertEquals(PermissionState.DENY,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK),
                "the derived view stays on the old generation too");
    }

    @Test
    void validatedPreparePublishesExactlyThePreparedGeneration() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
        PermissionDefaultsCache cache = new PermissionDefaultsCache(
                service::current, name -> Optional.empty(), ignored -> {
                });
        service.addListener(cache);
        service.addPreparer(cache::prepareFor);
        loader.yaml = BASE.replace("BLOCK_BREAK: DENY", "BLOCK_BREAK: ALLOW");

        ReloadOutcome outcome = service.tryReload(ConfigReloadPolicy::check);

        assertEquals(ReloadOutcome.Kind.SUCCESS, outcome.kind());
        assertEquals(PermissionState.ALLOW,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK),
                "one publish moves the snapshot and its derived view together");
        assertEquals(service.current().globalPolicyEpoch(),
                cache.view().globalPolicyEpoch(),
                "the derived view must carry the published generation's epoch");
    }

    @Test
    void failedPrepareLeavesCleanStateForTheNextAttempt() {
        MutableLoader loader = new MutableLoader();
        ConfigService service = new ConfigService(loader, ConfigSchema.parseYamlText(BASE));
        boolean[] sabotage = {true};
        service.addPreparer(loaded -> {
            if (sabotage[0]) {
                throw new RuntimeException("first attempt fails");
            }
        });
        loader.yaml = BASE.replace("BLOCK_BREAK: DENY", "BLOCK_BREAK: ALLOW");

        assertEquals(ReloadOutcome.Kind.FAILED, service.tryReload(ConfigReloadPolicy::check).kind());

        sabotage[0] = false;
        ReloadOutcome retry = service.tryReload(ConfigReloadPolicy::check);
        assertEquals(ReloadOutcome.Kind.SUCCESS, retry.kind());
        assertEquals(1L, service.current().globalPolicyEpoch());
    }
}
