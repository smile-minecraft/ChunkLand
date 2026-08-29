package com.smile.chunkland.api.land;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LandNameContractTest {

    @Test
    void homeVariantsShareNameKey() {
        var a = LandName.of("Home");
        var b = LandName.of("home");
        var c = LandName.of(" HOME ");
        assertEquals("home", a.nameKey());
        assertEquals("home", b.nameKey());
        assertEquals("home", c.nameKey());
        assertEquals(a.nameKey(), b.nameKey());
        assertEquals(a.nameKey(), c.nameKey());
    }

    @Test
    void displayNamePreservedExactly() {
        var named = LandName.of(" HOME ");
        assertEquals(" HOME ", named.displayName());
        assertEquals("home", named.nameKey());
    }

    @Test
    void blankDisplayNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> LandName.of(""));
        assertThrows(IllegalArgumentException.class, () -> LandName.of("   "));
        assertThrows(IllegalArgumentException.class, () -> LandName.of(" \t "));
    }

    @Test
    void controlCharactersRejected() {
        assertThrows(IllegalArgumentException.class, () -> LandName.of("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> LandName.of("a\tb"));
        assertThrows(IllegalArgumentException.class, () -> LandName.of("a\rb"));
        assertThrows(IllegalArgumentException.class, () -> LandName.of("" + (char) 0x07));
        assertThrows(IllegalArgumentException.class, () -> LandName.of("" + (char) 0x1B));
    }

    @Test
    void unicodeWhitespaceOnlyRejectedEverywhere() {
        var owner = OwnerRef.server();
        // U+2003 EM SPACE is Unicode whitespace; trim() misses it, strip() removes it.
        assertThrows(IllegalArgumentException.class, () -> LandName.of("\u2003"));
        assertThrows(IllegalArgumentException.class, () -> LandName.of(" \u2003 "));
        assertThrows(IllegalArgumentException.class, () -> new LandNameKey(owner, "\u2003"));
        assertThrows(IllegalArgumentException.class,
                () -> LandNameUniqueness.isAvailable(owner, "\u2003", new HashSet<>()));
    }

    @Test
    void unicodeWhitespaceAroundHomeNormalizesAndPreserves() {
        var named = LandName.of("\u2003Home\u2003");
        assertEquals("home", named.nameKey());
        assertEquals("\u2003Home\u2003", named.displayName());
    }

    @Test
    void nameKeyStableUnderTurkishLocale() {
        var prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.of("tr", "TR"));
            // Turkish locale would map I -> ı without Locale.ROOT; we require i.
            var named = LandName.of("Istanbul");
            assertEquals("istanbul", named.nameKey());
            var dotless = LandName.of("I");
            assertEquals("i", dotless.nameKey());
        } finally {
            Locale.setDefault(prev);
        }
    }

    @Test
    void unicodeLowercaseStable() {
        var named = LandName.of("Café");
        assertEquals("café", named.nameKey());
        var named2 = LandName.of("ΜΌΥΣΑ");
        assertEquals("μόυσα", named2.nameKey());
    }

    @Test
    void ownerScopedDuplicateRejected() {
        var owner = OwnerRef.server();
        var existing = new HashSet<LandNameKey>();
        existing.add(new LandNameKey(owner, "home"));

        // Same owner + same key -> rejected.
        assertThrows(IllegalArgumentException.class,
                () -> LandNameUniqueness.requireUnique(owner, "home", existing));
        // Same owner + different key -> allowed.
        LandNameUniqueness.requireUnique(owner, "garden", existing);
        // Different owner + same key -> allowed.
        LandNameUniqueness.requireUnique(OwnerRef.player(java.util.UUID.randomUUID()), "home", existing);
    }

    @Test
    void ownerScopedAvailability() {
        var ownerA = OwnerRef.server();
        var ownerB = OwnerRef.player(java.util.UUID.randomUUID());
        var existing = new HashSet<LandNameKey>();
        existing.add(new LandNameKey(ownerA, "home"));

        assertTrue(LandNameUniqueness.isAvailable(ownerA, "garden", existing));
        assertTrue(LandNameUniqueness.isAvailable(ownerB, "home", existing));
        assertEquals(false, LandNameUniqueness.isAvailable(ownerA, "home", existing));
    }

    @Test
    void nameKeyInvariantEnforced() {
        // nameKey must equal normalize(displayName); mismatched pair is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new LandName("Home", "garden"));
        // A correctly derived pair is accepted.
        var named = new LandName("Home", "home");
        assertEquals("Home", named.displayName());
        assertEquals("home", named.nameKey());
    }

    @Test
    void landNameKeyRejectsNonCanonical() {
        var owner = OwnerRef.server();
        assertThrows(IllegalArgumentException.class, () -> new LandNameKey(owner, "Home"));
        assertThrows(IllegalArgumentException.class, () -> new LandNameKey(owner, " HOME "));
        assertThrows(IllegalArgumentException.class, () -> new LandNameKey(owner, ""));
        assertThrows(IllegalArgumentException.class, () -> new LandNameKey(owner, "a\nb"));
        assertDoesNotThrow(() -> new LandNameKey(owner, "home"));
    }

    @Test
    void uniquenessRejectsNonCanonicalKey() {
        var owner = OwnerRef.server();
        var existing = new HashSet<LandNameKey>();
        assertThrows(IllegalArgumentException.class,
                () -> LandNameUniqueness.requireUnique(owner, "Home", existing));
        assertThrows(IllegalArgumentException.class,
                () -> LandNameUniqueness.isAvailable(owner, " HOME ", existing));
    }

    @Test
    void nameKeyEqualityByValue() {
        assertEquals(new LandNameKey(OwnerRef.server(), "home"),
                new LandNameKey(OwnerRef.server(), "home"));
        assertNotEquals(new LandNameKey(OwnerRef.server(), "home"),
                new LandNameKey(OwnerRef.server(), "garden"));
    }
}
