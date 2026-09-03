package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class InitialProtectionDepthTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final int WORLD_MIN = -64;
    private static final int WORLD_MAX = 320;

    @Test
    void exampleY70Y64Buffer5Gives59() {
        assertEquals(59, InitialProtectionDepth.resolve(70, 64, 5, WORLD_MIN, WORLD_MAX));
        assertEquals(59, InitialProtectionDepth.resolve(
                new SelectionPoint(WORLD, 0, 70, 0),
                new SelectionPoint(WORLD, 0, 64, 0),
                5, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void bothPointsSameY() {
        assertEquals(95, InitialProtectionDepth.resolve(100, 100, 5, WORLD_MIN, WORLD_MAX));
        assertEquals(100, InitialProtectionDepth.resolve(100, 100, 0, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void bufferZeroReturnsLowerY() {
        assertEquals(64, InitialProtectionDepth.resolve(70, 64, 0, WORLD_MIN, WORLD_MAX));
        assertEquals(70, InitialProtectionDepth.resolve(70, 70, 0, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void orderIndependent() {
        assertEquals(
                InitialProtectionDepth.resolve(64, 70, 5, WORLD_MIN, WORLD_MAX),
                InitialProtectionDepth.resolve(70, 64, 5, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void singleChunkUsesBlockYMinusBuffer() {
        assertEquals(59, InitialProtectionDepth.resolveSingle(64, 5, WORLD_MIN, WORLD_MAX));
        assertEquals(64, InitialProtectionDepth.resolveSingle(64, 0, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void clampToWorldMin() {
        assertEquals(WORLD_MIN, InitialProtectionDepth.resolve(-60, -60, 10, WORLD_MIN, WORLD_MAX));
        assertEquals(WORLD_MIN, InitialProtectionDepth.resolve(-64, 70, 5, WORLD_MIN, WORLD_MAX));
        // deep buffer pushes below min
        assertEquals(WORLD_MIN, InitialProtectionDepth.resolve(0, 0, 100, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void clampToWorldMax() {
        // when selection Y is above world max, clamp down
        assertEquals(WORLD_MAX, InitialProtectionDepth.resolve(400, 400, 5, WORLD_MIN, WORLD_MAX));
        assertEquals(WORLD_MAX, InitialProtectionDepth.resolve(325, 325, 0, WORLD_MIN, WORLD_MAX));
        // buffer still applied before clamp
        assertEquals(WORLD_MAX, InitialProtectionDepth.resolve(330, 330, 5, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void extremeYValuesWithoutOverflow() {
        assertEquals(WORLD_MIN, InitialProtectionDepth.resolve(Integer.MIN_VALUE, Integer.MIN_VALUE, 5, WORLD_MIN, WORLD_MAX));
        assertEquals(WORLD_MAX, InitialProtectionDepth.resolve(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, WORLD_MIN, WORLD_MAX));
        assertEquals(WORLD_MIN, InitialProtectionDepth.resolve(Integer.MIN_VALUE, 320, 0, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void bufferNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> InitialProtectionDepth.resolve(70, 64, -1, WORLD_MIN, WORLD_MAX));
        assertThrows(IllegalArgumentException.class, () -> InitialProtectionDepth.resolveSingle(64, -1, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void worldBoundsRejectedWhenMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class, () -> InitialProtectionDepth.resolve(70, 64, 5, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> InitialProtectionDepth.resolveSingle(64, 5, 100, 0));
    }

    @Test
    void selectionPointsRequireSameWorld() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000011");
        assertThrows(IllegalArgumentException.class, () -> InitialProtectionDepth.resolve(
                new SelectionPoint(WORLD, 0, 70, 0),
                new SelectionPoint(other, 0, 64, 0),
                5, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void nullChecks() {
        assertThrows(NullPointerException.class, () -> InitialProtectionDepth.resolve(
                (SelectionPoint) null, new SelectionPoint(WORLD, 0, 64, 0), 5, WORLD_MIN, WORLD_MAX));
        assertThrows(NullPointerException.class, () -> InitialProtectionDepth.resolve(
                new SelectionPoint(WORLD, 0, 70, 0), null, 5, WORLD_MIN, WORLD_MAX));
    }

    @Test
    void noTerrainScanningOrChunkLoading() throws IOException {
        Path root = findProjectRoot();
        Path target = root.resolve("chunkland-plugin/src/main/java/com/smile/chunkland/selection/InitialProtectionDepth.java");
        // also scan directory for selection depth related files
        List<Path> files;
        Path dir = root.resolve("chunkland-plugin/src/main/java/com/smile/chunkland/selection");
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().toLowerCase().contains("depth")
                            || p.getFileName().toString().equals("InitialProtectionDepth.java"))
                    .toList();
        }
        assertFalse(files.isEmpty(), "InitialProtectionDepth.java must exist");
        for (Path file : files) {
            String src = Files.readString(file).toLowerCase();
            assertFalse(src.contains("org.bukkit"), "must not reference Bukkit: " + file);
            assertFalse(src.contains("heightmap"), "must not reference HeightMap: " + file);
            assertFalse(src.contains("getchunk"), "must not load chunk: " + file);
            assertFalse(src.contains("loadchunk"), "must not load chunk: " + file);
            assertFalse(src.contains("gethighestblock"), "must not scan terrain: " + file);
            assertFalse(src.contains("blockdata"), "must not scan BlockData: " + file);
            assertFalse(src.contains("getblockat"), "must not scan terrain via getBlockAt: " + file);
            assertFalse(src.contains("world.get"), "must not access World: " + file);
            assertFalse(src.contains("heightmap"), "must not use HeightMap: " + file);
            // ensure no Chunk load via WorldChunkIndex? but pure function should not depend on it either
            String raw = Files.readString(file);
            // disallow imports of Bukkit
            for (String line : raw.lines().toList()) {
                String t = line.trim();
                if (t.startsWith("import")) {
                    assertFalse(t.contains("org.bukkit"), "must not import Bukkit: " + line);
                    assertFalse(t.contains("net.minecraft"), "must not import NMS: " + line);
                }
            }
        }
        // also verify main source file exists
        assertTrue(Files.exists(target), "InitialProtectionDepth.java missing at " + target);
    }

    private static Path findProjectRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("chunkland-api"))) return cur;
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        return Paths.get("").toAbsolutePath();
    }
}
