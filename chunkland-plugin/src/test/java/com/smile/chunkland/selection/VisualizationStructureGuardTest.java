package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the particle-visualization production path.
 *
 * <p>Visualization geometry may only come from the session snapshot and pure chunk math.
 * These tests fail the build if production visualization code ever reaches for terrain
 * queries, entity scans, broadcast sends, or a global scheduler — the exact properties the
 * task acceptance criteria pin down. The single allowed player reads are the operating
 * player's own scheduler-bound context ({@code getScheduler()}), its own location for
 * render-distance culling, and its own player-scoped {@code spawnParticle} send.
 */
class VisualizationStructureGuardTest {
    /** Tokens that must never appear in production visualization sources. */
    private static final List<String> FORBIDDEN = List.of(
            "getHighestBlock",
            "getBlockData(",
            ".getBlock(",
            "getChunkAt",
            "getWorld(",
            "getNearbyEntities",
            "getNearbyPlayers",
            "spawnEntity",
            "removeEntity",
            "readBlock",
            "writeBlock",
            "playEffect",
            "findNearby",
            "broadcast(",
            "runGlobal",
            "runAsync(",
            "getGlobalRegionScheduler",
            "getAsyncScheduler",
            "Bukkit.getScheduler",
            "WorldService",
            "EntityReference",
            "import org.bukkit.World;",
            "import org.bukkit.Chunk;",
            "import org.bukkit.block",
            "Executors.",
            "newSingleThread",
            "newCachedThreadPool",
            "BukkitRunnable",
            "runTaskTimer");

    @Test
    void productionVisualizationNeverTouchesTerrainEntityOrGlobalSchedulerApis() throws IOException {
        Path selectionDir = locateSelectionDir();
        List<Path> visualized = new ArrayList<>();
        try (Stream<Path> files = Files.list(selectionDir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.contains("Visual")) {
                    visualized.add(file);
                }
            }
        }
        assertFalse(visualized.isEmpty(), "expected production visualization sources in " + selectionDir);

        List<String> violations = new ArrayList<>();
        for (Path file : visualized) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN) {
                if (content.contains(token)) {
                    violations.add(file.getFileName() + " contains forbidden '" + token + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), "visualization structure violations: " + violations);
    }

    @Test
    void everyVisualizationSourceDocumentsTheNoTerrainQueryRule() throws IOException {
        Path selectionDir = locateSelectionDir();
        List<String> undocumented = new ArrayList<>();
        try (Stream<Path> files = Files.list(selectionDir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (!name.contains("Visual")) {
                    continue;
                }
                // Seams without behavior (pure callback interfaces) are exempt from the
                // documentation requirement; everything that computes or sends must state it.
                if (name.equals("SelectionVisualizationTaskController.java")
                        || name.equals("VisualizationTickScheduler.java")
                        || name.equals("SelectionParticleSink.java")) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (!content.contains("Highest Block") && !content.contains("terrain")) {
                    undocumented.add(name);
                }
            }
        }
        assertTrue(undocumented.isEmpty(), "visualization sources missing the terrain rule note: " + undocumented);
    }

    private static Path locateSelectionDir() {
        for (String candidate : List.of(
                "src/main/java/com/smile/chunkland/selection",
                "chunkland-plugin/src/main/java/com/smile/chunkland/selection")) {
            Path dir = Path.of(candidate);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        fail("cannot locate production selection sources from working dir " + Path.of("").toAbsolutePath());
        throw new AssertionError("unreachable");
    }
}
