package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.rule.LandRuleMutationService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production rule-service assembly: the rule service publishes into the
 * shared authorisation cache, so rule mutations land in the same snapshot
 * the trust path publishes into.
 */
class LandRuleWiringTest {

    @TempDir Path tmp;

    @Test
    void ruleBuilderSharesAuthorisationCache() {
        Path path = tmp.resolve(UUID.randomUUID() + ".db");
        try (PersistenceStore store = PersistenceStore.open(path)) {
            LandAuthorisationCache cache = new LandAuthorisationCache();
            LandRuleMutationService built = ChunkLandPlugin.buildLandRules(
                    store, cache, PublicEvents.create(null, null));
            assertSame(cache, built.cache(),
                    "the production rule service must publish into the shared authorisation cache");
        }
    }
}
