package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Conservative in-memory defaults: the floor a damaged or vanished config
 * falls back to when no last-known-good copy exists.
 *
 * <p>Every listed world is unclaimable full-height protection, every subject
 * default stays {@code INHERIT} (fail-closed {@code DENY}), every rule
 * default — including the one built-in {@code ALLOW} — is pinned to
 * {@code DENY}, and pricing is unavailable.
 */
class ConservativeConfigFactoryTest {

    @Test
    void everyListedWorldIsUnclaimableFullHeight() {
        ChunkLandConfig snapshot =
                ConservativeConfigFactory.forWorlds(List.of("world", "world_nether", "world_the_end"));

        assertEquals(3, snapshot.worlds().size());
        for (String name : List.of("world", "world_nether", "world_the_end")) {
            assertEquals(new WorldSettings(false, VerticalMode.FULL_HEIGHT),
                    snapshot.worlds().get(name), "world " + name);
        }
    }

    @Test
    void subjectDefaultsStayInherit() {
        ChunkLandConfig snapshot = ConservativeConfigFactory.forWorlds(List.of("world"));

        assertTrue(snapshot.subjectDefaults().global().isEmpty());
        assertTrue(snapshot.subjectDefaults().worlds().isEmpty());
    }

    @Test
    void everyRuleDefaultIsDenyIncludingBuiltInAllow() {
        ChunkLandConfig snapshot = ConservativeConfigFactory.forWorlds(List.of("world"));

        for (LandRuleType rule : LandRuleType.values()) {
            assertEquals(PermissionState.DENY, snapshot.ruleDefaults().global().get(rule),
                    "rule " + rule + " must be DENY in conservative mode");
        }
        assertTrue(snapshot.ruleDefaults().worlds().isEmpty());
    }

    @Test
    void economyIsUnavailable() {
        ChunkLandConfig snapshot = ConservativeConfigFactory.forWorlds(List.of("world"));

        assertNull(snapshot.economy(), "conservative pricing fails closed, never zero-priced");
    }
}
