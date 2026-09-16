package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogQueryParserTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @Test
    void eachFilterParsesIndividually() {
        UUID actor = UUID.randomUUID();
        AuditSearchQuery byActor = AuditLogQueryParser.parse("u:" + actor, NOW);
        assertEquals(actor, byActor.actor());

        AuditSearchQuery byTime = AuditLogQueryParser.parse("t:7d", NOW);
        assertEquals(NOW.minusSeconds(7L * 86400L), byTime.since());

        AuditSearchQuery byAction = AuditLogQueryParser.parse("a:LAND_CREATE", NOW);
        assertEquals("LAND_CREATE", byAction.action());

        LandId land = new LandId(UUID.randomUUID());
        AuditSearchQuery byLand = AuditLogQueryParser.parse("land:" + land.value(), NOW);
        assertEquals(land, byLand.landId());

        UUID world = UUID.randomUUID();
        AuditSearchQuery byWorld = AuditLogQueryParser.parse("world:" + world, NOW);
        assertEquals(world, byWorld.worldId());
    }

    @Test
    void combinedFiltersParseTogether() {
        UUID actor = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        UUID world = UUID.randomUUID();
        AuditSearchQuery query = AuditLogQueryParser.parse(
                "u:" + actor + " t:24h a:LAND_RENAME land:" + land.value() + " world:" + world, NOW);
        assertEquals(actor, query.actor());
        assertEquals(NOW.minusSeconds(86400L), query.since());
        assertEquals("LAND_RENAME", query.action());
        assertEquals(land, query.landId());
        assertEquals(world, query.worldId());
    }

    @Test
    void depthAliasResolvesToDepthExtend() {
        AuditSearchQuery query = AuditLogQueryParser.parse("a:depth", NOW);
        assertEquals("DEPTH_EXTEND", query.action());
    }

    @Test
    void timeSuffixesAreSupported() {
        assertEquals(NOW.minusSeconds(30), AuditLogQueryParser.parse("t:30s", NOW).since());
        assertEquals(NOW.minusSeconds(15L * 60L), AuditLogQueryParser.parse("t:15m", NOW).since());
        assertEquals(NOW.minusSeconds(24L * 3600L), AuditLogQueryParser.parse("t:24h", NOW).since());
        assertEquals(NOW.minusSeconds(7L * 86400L), AuditLogQueryParser.parse("t:7d", NOW).since());
    }

    @Test
    void paginationTokensMapToLimitAndOffset() {
        AuditSearchQuery query = AuditLogQueryParser.parse("a:LAND_CREATE limit:10 page:3", NOW);
        assertEquals(10, query.limit());
        assertEquals(20, query.offset());
    }

    @Test
    void blankInputYieldsPagedDefaults() {
        AuditSearchQuery query = AuditLogQueryParser.parse("   ", NOW);
        assertEquals(20, query.limit());
        assertEquals(0, query.offset());
        assertNull(query.actor());
        assertNull(query.since());
        assertNull(query.action());
        assertNull(query.landId());
        assertNull(query.worldId());
    }

    @Test
    void prefixMatchingIsCaseInsensitive() {
        UUID actor = UUID.randomUUID();
        AuditSearchQuery query = AuditLogQueryParser.parse("U:" + actor + " A:land_create", NOW);
        assertEquals(actor, query.actor());
        assertEquals("LAND_CREATE", query.action());
    }

    @Test
    void invalidTokensFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("x:1", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("u:not-a-uuid", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("t:forever", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("t:-7d", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("a:  ", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("land:not-a-uuid", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("world:not-a-uuid", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("limit:0", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse("page:0", NOW));
        assertThrows(IllegalArgumentException.class, () -> AuditLogQueryParser.parse(null, NOW));
        assertThrows(NullPointerException.class, () -> AuditLogQueryParser.parse("a:LAND_CREATE", null));
    }
}
