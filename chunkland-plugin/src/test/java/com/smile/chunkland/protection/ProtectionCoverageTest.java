package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Public P1 coverage table: every action sits in exactly one tier with a
 * stated mechanism, and every action keeps its declared decision source.
 */
class ProtectionCoverageTest {

    @Test
    void everyActionSitsInExactlyOneTier() {
        var table = ProtectionCoverage.table();
        assertEquals(ProtectionActionType.values().length, table.size(),
                "coverage must list every action exactly once, got: " + table.keySet());
        Set<ProtectionActionType> seen = EnumSet.noneOf(ProtectionActionType.class);
        for (var entry : table.values()) {
            assertNotNull(entry, "coverage entry must not be null");
            assertTrue(seen.add(entry.action()),
                    "duplicate coverage entry for " + entry.action());
        }
        for (ProtectionActionType action : ProtectionActionType.values()) {
            assertTrue(table.containsKey(action), "unclassified action: " + action);
        }
    }

    @Test
    void everyEntryStatesTierMechanismAndSource() {
        for (var entry : ProtectionCoverage.table().values()) {
            assertNotNull(entry.tier(), entry.action() + " must declare a tier");
            assertNotNull(entry.mechanism(), entry.action() + " must state a mechanism");
            assertFalse(entry.mechanism().isBlank(),
                    entry.action() + " mechanism must not be blank");
            assertNotNull(entry.action().decisionSource(),
                    entry.action() + " must declare a DecisionSource");
            assertEquals(entry.action().decisionSource(),
                    ProtectionCoverage.coverageOf(entry.action()).action().decisionSource());
        }
    }

    @Test
    void p0TierHoldsTheTwentyOneInterceptedActions() {
        long p0 = ProtectionCoverage.table().values().stream()
                .filter(e -> e.tier() == ProtectionCoverage.Tier.P0_ENFORCED)
                .count();
        assertEquals(21, p0, "P0 tier must hold the 21 previously intercepted actions");
    }

    @Test
    void redstoneAndEntityInteractAreP1Enforced() {
        assertEquals(ProtectionCoverage.Tier.P1_ENFORCED,
                ProtectionCoverage.coverageOf(ProtectionActionType.REDSTONE_USE).tier());
        assertEquals(ProtectionCoverage.Tier.P1_ENFORCED,
                ProtectionCoverage.coverageOf(ProtectionActionType.ENTITY_INTERACT).tier());
    }
}
