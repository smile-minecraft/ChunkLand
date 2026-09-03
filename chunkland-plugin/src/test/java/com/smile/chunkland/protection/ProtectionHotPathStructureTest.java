package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ProtectionHotPathStructureTest {

    private static Path findProjectRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cur.resolve("settings.gradle.kts"))
                    && Files.isDirectory(cur.resolve("chunkland-api"))) {
                return cur;
            }
            Path parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        return Paths.get("").toAbsolutePath();
    }

    private static Path protectionDir() {
        Path root = findProjectRoot();
        Path cand = root.resolve(
                "chunkland-plugin/src/main/java/com/smile/chunkland/protection");
        if (Files.isDirectory(cand)) {
            return cand;
        }
        Path fallback = Paths.get(
                "chunkland-plugin/src/main/java/com/smile/chunkland/protection");
        if (Files.isDirectory(fallback)) {
            return fallback;
        }
        return cand;
    }

    private static List<Path> sources() throws IOException {
        Path dir = protectionDir();
        assertTrue(Files.isDirectory(dir), "protection package must exist: " + dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void hotPathTouchesNoStorageOrEconomyOrChunkLoading() throws IOException {
        for (Path file : sources()) {
            for (String line : Files.readAllLines(file)) {
                String t = line.trim().toLowerCase();
                assertFalse(t.contains("java.sql"), "hot path must not use SQL: " + line);
                assertFalse(t.contains("jdbc"), "hot path must not use JDBC: " + line);
                assertFalse(t.contains("sqlite"), "hot path must not use SQLite: " + line);
                assertFalse(t.contains("economy"), "hot path must not touch economy: " + line);
                assertFalse(t.contains("vault"), "hot path must not touch Vault: " + line);
                assertFalse(t.contains("loadchunk"), "hot path must not load chunks: " + line);
                assertFalse(t.contains("getchunk"), "hot path must not fetch chunks: " + line);
                assertFalse(t.contains("thread.sleep"), "hot path must not sleep: " + line);
                assertFalse(t.contains("synchronized"), "hot path must not block: " + line);
                assertFalse(t.contains(".wait("), "hot path must not wait: " + line);
                assertFalse(t.contains("future.get"), "hot path must not wait on futures: " + line);
                assertFalse(t.contains("minimessage"), "hot path must not render messages: " + line);
                assertFalse(t.contains("net.kyori"), "hot path must not use Adventure: " + line);
            }
        }
    }

    @Test
    void engineAndRegistryStayBukkitFree() throws IOException {
        for (String name : List.of("ProtectionEngine.java", "ProtectionActionRegistry.java")) {
            Path file = protectionDir().resolve(name);
            assertTrue(Files.exists(file), "missing hot-path source: " + name);
            String src = Files.readString(file);
            assertFalse(src.contains("org.bukkit"),
                    name + " must stay Bukkit-free so region threads only read snapshots");
            assertFalse(src.contains("acelib"),
                    name + " must not depend on AceLib");
        }
    }

    @Test
    void noConcreteWorkflowIdsInProtectionSources() throws IOException {
        for (Path file : sources()) {
            String src = Files.readString(file);
            assertFalse(src.contains("CL-M"),
                    "executable source must not carry concrete workflow IDs: " + file);
        }
    }
}
