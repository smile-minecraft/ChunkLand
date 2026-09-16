package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditEntry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogFormatterTest {

    private static final Instant WHEN = Instant.parse("2026-09-16T12:00:00Z");

    private AuditEntry entry() {
        return new AuditEntry(42L, WHEN, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "LAND_CREATE", new LandId(UUID.fromString("123e4567-e89b-12d3-a456-426614174001")),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174002"),
                null, 1, null, null, null, List.of());
    }

    @Test
    void storedEntryCarriesNoLocale() {
        boolean hasLocale = Arrays.stream(AuditEntry.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase(Locale.ROOT).contains("locale"));
        assertFalse(hasLocale);
    }

    @Test
    void sameEntryRendersDifferentlyPerLocale() {
        AuditEntry stored = entry();
        String english = AuditLogFormatter.format(stored, Locale.US);
        String traditional = AuditLogFormatter.format(stored, Locale.forLanguageTag("zh-TW"));
        assertTrue(english.contains("LAND_CREATE"));
        assertTrue(traditional.contains("LAND_CREATE"));
        assertNotEquals(english, traditional);
    }

    @Test
    void renderingIsDeterministicForSameLocale() {
        AuditEntry stored = entry();
        assertEquals(AuditLogFormatter.format(stored, Locale.US),
                AuditLogFormatter.format(stored, Locale.US));
    }

    @Test
    void nullLocaleFallsBackToDefault() {
        AuditEntry stored = entry();
        assertDoesNotThrow(() -> AuditLogFormatter.format(stored, null));
        assertEquals(AuditLogFormatter.format(stored, Locale.US),
                AuditLogFormatter.format(stored, null));
    }

    @Test
    void nullEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditLogFormatter.format(null, Locale.US));
    }
}
