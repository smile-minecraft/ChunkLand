package com.smile.chunkland.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.runtime.index.SubLandIndex;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard: runtime index must not reference Bukkit, SQL, I/O or ChunkKey on hot path.
 */
class RuntimeIndexStructureTest {

    private static Path runtimeDir() {
        // Try project-root relative, then module relative
        Path a = Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/index");
        if (Files.isDirectory(a)) return a;
        Path b = Paths.get("src/main/java/com/smile/chunkland/runtime/index");
        if (Files.isDirectory(b)) return b;
        Path c = Paths.get("").toAbsolutePath().resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/index");
        if (Files.isDirectory(c)) return c;
        Path d = Paths.get("").toAbsolutePath().resolve("src/main/java/com/smile/chunkland/runtime/index");
        if (Files.isDirectory(d)) return d;
        return a;
    }

    private List<String> readAll() throws IOException {
        Path dir = runtimeDir();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        try { return Files.lines(p); } catch (IOException e) { throw new RuntimeException(e); }
                    }).toList();
        }
    }

    @Test
    void noBukkitImports() throws IOException {
        for (String line : readAll()) {
            String t = line.trim();
            if (t.startsWith("import")) {
                assertFalse(t.contains("org.bukkit"), "forbidden Bukkit import: " + t);
                assertFalse(t.contains("paper"), "forbidden Paper import: " + t);
                assertFalse(t.contains("folia"), "forbidden Folia import: " + t);
                assertFalse(t.contains("acelib"), "forbidden AceLib import: " + t);
                assertFalse(t.contains("sqlite"), "forbidden SQLite import: " + t);
                assertFalse(t.contains("java.sql"), "forbidden SQL import: " + t);
                assertFalse(t.contains("javax.sql"), "forbidden SQL import: " + t);
            }
        }
    }

    @Test
    void hotPathDoesNotAllocateChunkKey() throws IOException {
        // scan runtime index sources for forbidden hot-path allocations
        for (Path p : Files.list(runtimeDir()).toList()) {
            String content = Files.readString(p);
            // hot path must not construct ChunkKey nor use streams/temporary collections
            if (p.getFileName().toString().equals("WorldChunkIndex.java")) {
                assertFalse(content.contains("new ChunkKey"), "WorldChunkIndex must not allocate ChunkKey: " + p);
            }
            if (p.getFileName().toString().equals("LandRegistry.java")) {
                // hot methods findLand/findLandId must not use ChunkKey or streams
                // we check the method body does not contain "ChunkKey" or ".stream()"
                // allow ChunkKey in non-hot from() builder which iterates snapshots (expected)
                // but hot methods are findLand* which should not contain ChunkKey
                String hotSection = content.substring(content.indexOf("findLandId"));
                assertFalse(hotSection.contains("new ChunkKey"), "hot path must not allocate ChunkKey");
                assertFalse(hotSection.contains(".stream()"), "hot path must not use streams");
            }
        }
    }

    @Test
    void noIoOrSqlReferences() throws IOException {
        for (String line : readAll()) {
            String t = line.toLowerCase();
            assertFalse(t.contains("java.io.file"), "forbidden I/O: " + line);
            assertFalse(t.contains("java.nio.file.files.write"), "forbidden I/O: " + line);
            assertFalse(t.contains("jdbc"), "forbidden JDBC: " + line);
        }
    }

    @Test
    void playerCacheDoesNotImportBukkit() throws IOException {
        Path pc = runtimeDir().resolve("PlayerLocation.java");
        String c = Files.readString(pc);
        assertFalse(c.contains("org.bukkit"), "PlayerLocation must not import Bukkit: " + c);
        assertFalse(c.contains("import org.bukkit"), "no Bukkit import");
        // must not have Bukkit type fields, only UUID/int
        assertFalse(c.toLowerCase().contains("org.bukkit.entity.player"), "must not store Bukkit Player");
    }

    /**
     * Regression guard: {@code SubLandIndex.lengths} must be wide enough to hold
     * the slice length of any legal Y-stack candidate list. Storing it as
     * {@code short[]} silently truncates values above {@code Short.MAX_VALUE},
     * making {@code findAtBlock} skip the entire slice and return null for
     * every Y inside the stack.
     */
    @Test
    void subLandIndexLengthsFieldIsWideEnoughForLargeSlices() throws Exception {
        Field f = SubLandIndex.class.getDeclaredField("lengths");
        f.setAccessible(true);
        assertSame(int.class, f.getType().getComponentType(),
                "SubLandIndex.lengths must be int[]; short[] overflows for legal "
                        + "stacks above Short.MAX_VALUE candidates");
    }
}
