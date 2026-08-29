package com.smile.chunkland.api.land;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Immutability and legality invariants for the Land / SubLand domain model.
 *
 * <p>Every mutation must return a new object without altering the receiver, all
 * exposed collections must be immutable, and construction must reject
 * cross-world chunks, negative revisions, reversed timestamps, and non-canonical
 * names. The forbidden-import scan guards the "domain layer holds no Bukkit /
 * Paper / SQL / AceLib type" contract for downstream containment / repository /
 * runtime-index work.
 */
class LandImmutableModelTest {

    private final UUID worldA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID worldB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private LandSnapshot baseLand() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0), new ChunkKey(worldA, 1, 0));
        var subs = List.<SubLandSnapshot>of();
        return new LandSnapshot(
                new LandId(UUID.randomUUID()), "Home", "home",
                OwnerRef.server(), worldA, chunks, subs, 1L, 2L,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(10));
    }

    // ---- Land construction invariants ----

    @Test
    void landRejectsMixedWorldChunks() {
        var chunks = new HashSet<ChunkKey>();
        chunks.add(new ChunkKey(worldA, 0, 0));
        chunks.add(new ChunkKey(worldB, 1, 1));
        var subs = List.<SubLandSnapshot>of();
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landRejectsChunkWorldNotMatchingField() {
        var chunks = Set.of(new ChunkKey(worldB, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landRejectsNegativeRevisions() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, -1L, 2L, Instant.EPOCH, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, -2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landRejectsReversedTimestamps() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L,
                        Instant.EPOCH.plusSeconds(10), Instant.EPOCH));
    }

    @Test
    void landRejectsNonCanonicalNameKey() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        // displayName "Home" normalizes to "home"; "HOME" is not canonical.
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "HOME",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    // ---- SubLand construction invariants ----

    @Test
    void subLandRejectsMixedWorldChunks() {
        var chunks = new HashSet<ChunkKey>();
        chunks.add(new ChunkKey(worldA, 0, 0));
        chunks.add(new ChunkKey(worldB, 1, 1));
        var parent = new LandId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10, chunks));
    }

    // ---- Land subland parent / world consistency ----

    @Test
    void landRejectsNullSubLand() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = new ArrayList<SubLandSnapshot>();
        subs.add(null);
        assertThrows(NullPointerException.class,
                () -> new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landRejectsWrongParentSubLand() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var landId = new LandId(UUID.randomUUID());
        var wrongParent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), wrongParent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 2, 2)));
        var subs = List.of(sub);
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(landId, "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landRejectsForeignWorldSubLandChunk() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var landId = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), landId, null, 0, 10,
                Set.of(new ChunkKey(worldB, 2, 2)));
        var subs = List.of(sub);
        assertThrows(IllegalArgumentException.class,
                () -> new LandSnapshot(landId, "Home", "home",
                        OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void landAcceptsEmptySubLandWithMatchingParent() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var landId = new LandId(UUID.randomUUID());
        // An empty subland is representable; its world cannot be derived from an
        // empty chunk set, so only the parent id is checked.
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), landId, null, 0, 10, Set.of());
        var subs = List.of(sub);
        assertDoesNotThrow(() -> new LandSnapshot(landId, "Home", "home",
                OwnerRef.server(), worldA, chunks, subs, 1L, 2L, Instant.EPOCH, Instant.EPOCH));
    }

    // ---- Revision overflow ----

    @Test
    void landStructureRevisionOverflowThrows() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        var land = new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                OwnerRef.server(), worldA, chunks, subs, Long.MAX_VALUE, 2L, Instant.EPOCH, Instant.EPOCH);
        assertThrows(ArithmeticException.class, () -> land.addChunk(new ChunkKey(worldA, 5, 5)));
    }

    @Test
    void landPolicyRevisionOverflowThrows() {
        var chunks = Set.of(new ChunkKey(worldA, 0, 0));
        var subs = List.<SubLandSnapshot>of();
        var land = new LandSnapshot(new LandId(UUID.randomUUID()), "Home", "home",
                OwnerRef.server(), worldA, chunks, subs, 1L, Long.MAX_VALUE, Instant.EPOCH, Instant.EPOCH);
        assertThrows(ArithmeticException.class, () -> land.withName("Garden", "garden"));
    }

    // ---- Land mutations return new objects, old unchanged ----

    @Test
    void landAddChunkReturnsNewObjectWithBumpedRevision() {
        var land = baseLand();
        var newChunk = new ChunkKey(worldA, 5, 5);
        var updated = land.addChunk(newChunk);
        assertNotSame(land, updated);
        assertEquals(land.structureRevision() + 1, updated.structureRevision());
        assertFalse(land.chunks().contains(newChunk));
        assertTrue(updated.chunks().contains(newChunk));
        assertEquals(land.landPolicyRevision(), updated.landPolicyRevision());
    }

    @Test
    void landRemoveChunkReturnsNewObject() {
        var land = baseLand();
        var victim = land.chunks().iterator().next();
        var updated = land.removeChunk(victim);
        assertNotSame(land, updated);
        assertEquals(land.structureRevision() + 1, updated.structureRevision());
        assertTrue(land.chunks().contains(victim));
        assertFalse(updated.chunks().contains(victim));
    }

    @Test
    void landReplaceChunksReturnsNewObject() {
        var land = baseLand();
        var replacement = Set.of(new ChunkKey(worldA, 7, 7));
        var updated = land.replaceChunks(replacement);
        assertNotSame(land, updated);
        assertEquals(land.structureRevision() + 1, updated.structureRevision());
        assertEquals(replacement, updated.chunks());
        assertEquals(baseLand().chunks(), land.chunks());
    }

    @Test
    void landWithOwnerReturnsNewObject() {
        var land = baseLand();
        var newOwner = OwnerRef.player(UUID.randomUUID());
        var updated = land.withOwner(newOwner);
        assertNotSame(land, updated);
        assertEquals(land.structureRevision() + 1, updated.structureRevision());
        assertEquals(newOwner, updated.ownerRef());
        assertEquals(OwnerRef.server(), land.ownerRef());
    }

    @Test
    void landWithNameBumpsPolicyRevision() {
        var land = baseLand();
        var updated = land.withName("Garden", "garden");
        assertNotSame(land, updated);
        assertEquals(land.landPolicyRevision() + 1, updated.landPolicyRevision());
        assertEquals("Garden", updated.displayName());
        assertEquals("garden", updated.nameKey());
        assertEquals(land.structureRevision(), updated.structureRevision());
    }

    @Test
    void landWithUpdatedAtKeepsRevisions() {
        var land = baseLand();
        var updated = land.withUpdatedAt(Instant.EPOCH.plusSeconds(100));
        assertNotSame(land, updated);
        assertEquals(land.structureRevision(), updated.structureRevision());
        assertEquals(land.landPolicyRevision(), updated.landPolicyRevision());
        assertEquals(Instant.EPOCH.plusSeconds(100), updated.updatedAt());
    }

    @Test
    void landAddSubLandReturnsNewObject() {
        var land = baseLand();
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), land.id(), null, 0, 10,
                Set.of(new ChunkKey(worldA, 2, 2)));
        var updated = land.addSubLand(sub);
        assertNotSame(land, updated);
        assertEquals(land.structureRevision() + 1, updated.structureRevision());
        assertFalse(land.subLands().contains(sub));
        assertTrue(updated.subLands().contains(sub));
    }

    // ---- SubLand mutations return new objects, old unchanged ----

    @Test
    void subLandAddChunkReturnsNewObject() {
        var parent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 0, 0)));
        var newChunk = new ChunkKey(worldA, 2, 2);
        var updated = sub.addChunk(newChunk);
        assertNotSame(sub, updated);
        assertFalse(sub.chunks().contains(newChunk));
        assertTrue(updated.chunks().contains(newChunk));
    }

    @Test
    void subLandRemoveChunkReturnsNewObject() {
        var parent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 0, 0), new ChunkKey(worldA, 1, 1)));
        var victim = new ChunkKey(worldA, 0, 0);
        var updated = sub.removeChunk(victim);
        assertNotSame(sub, updated);
        assertTrue(sub.chunks().contains(victim));
        assertFalse(updated.chunks().contains(victim));
    }

    @Test
    void subLandWithBoundsReturnsNewObject() {
        var parent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 0, 0)));
        var updated = sub.withBounds(5, 20);
        assertNotSame(sub, updated);
        assertEquals(5, updated.minBlockY());
        assertEquals(20, updated.maxBlockY());
        assertEquals(0, sub.minBlockY());
    }

    @Test
    void subLandWithNameReturnsNewObject() {
        var parent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 0, 0)));
        var updated = sub.withName("storage");
        assertNotSame(sub, updated);
        assertEquals("storage", updated.name());
        assertEquals(null, sub.name());
    }

    // ---- Returned collections are immutable ----

    @Test
    void landMutationReturnsImmutableChunks() {
        var base = baseLand();
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), base.id(), null, 0, 10,
                Set.of(new ChunkKey(worldA, 2, 2)));
        var land = base.addSubLand(sub);
        var updated = land.addChunk(new ChunkKey(worldA, 9, 9));
        assertThrows(UnsupportedOperationException.class,
                () -> updated.chunks().add(new ChunkKey(worldA, 8, 8)));
        assertThrows(UnsupportedOperationException.class,
                () -> updated.subLands().add(updated.subLands().get(0)));
    }

    @Test
    void subLandMutationReturnsImmutableChunks() {
        var parent = new LandId(UUID.randomUUID());
        var sub = new SubLandSnapshot(new SubLandId(UUID.randomUUID()), parent, null, 0, 10,
                Set.of(new ChunkKey(worldA, 0, 0)));
        var updated = sub.addChunk(new ChunkKey(worldA, 2, 2));
        assertThrows(UnsupportedOperationException.class,
                () -> updated.chunks().add(new ChunkKey(worldA, 3, 3)));
    }

    // ---- Domain layer holds no forbidden types ----

    @Test
    void landPackageHasNoForbiddenImports() throws IOException {
        // Resolve the land source directory from a set of candidate locations so the
        // scan is independent of how the test is launched (Gradle sets user.dir to the
        // module dir; a standalone launch may set it to the project root, and the
        // compiled-class location is used when a code source is available).
        var candidates = new ArrayList<Path>();
        String userDir = System.getProperty("user.dir");
        candidates.add(Paths.get(userDir, "src/main/java/com/smile/chunkland/api/land"));
        candidates.add(Paths.get(userDir, "chunkland-api/src/main/java/com/smile/chunkland/api/land"));
        try {
            var loc = LandImmutableModelTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path moduleDir = Paths.get(loc);
            for (int i = 0; i < 4; i++) {
                moduleDir = moduleDir.getParent();
            }
            candidates.add(moduleDir.resolve("src/main/java/com/smile/chunkland/api/land"));
        } catch (Exception ignored) {
            // code source unavailable in this launch; candidate list still covers it
        }
        Path base = candidates.stream().filter(Files::exists).findFirst().orElse(null);
        assertTrue(base != null && Files.exists(base), "land source dir not found among: " + candidates);
        var forbidden = List.of("org.bukkit", "org.spigotmc", "io.papermc", "net.kyori",
                "com.mojang", "java.sql", "javax.sql", "com.smile.acelib");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("import ")) {
                        for (String f : forbidden) {
                            if (line.contains(f)) {
                                violations.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Forbidden imports found in land package:\n" + String.join("\n", violations));
    }
}
