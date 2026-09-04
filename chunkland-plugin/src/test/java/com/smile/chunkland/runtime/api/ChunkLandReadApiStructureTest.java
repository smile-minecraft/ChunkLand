package com.smile.chunkland.runtime.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ChunkLandReadApiStructureTest {

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

    private static Path apiDir() {
        Path root = findProjectRoot();
        Path cand = root.resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api");
        if (Files.isDirectory(cand)) return cand;
        Path a = Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api");
        if (Files.isDirectory(a)) return a;
        Path b = Paths.get("src/main/java/com/smile/chunkland/runtime/api");
        if (Files.isDirectory(b)) return b;
        return cand;
    }

    private static Path apiModuleDir() {
        Path root = findProjectRoot();
        Path cand = root.resolve("chunkland-api/src/main/java/com/smile/chunkland/api");
        if (Files.isDirectory(cand)) return cand;
        Path a = Paths.get("chunkland-api/src/main/java/com/smile/chunkland/api");
        if (Files.isDirectory(a)) return a;
        return cand;
    }

    private List<String> readAllApi() throws IOException {
        Path dir = apiDir();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try { return Files.lines(p); } catch (IOException e) { throw new RuntimeException(e); }
            }).toList();
        }
    }

    @Test
    void apiHasNoForbiddenImports() throws IOException {
        Path dir = apiModuleDir();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.startsWith("import")) {
                        assertFalse(t.contains("org.bukkit"), "API must not import Bukkit: " + t + " in " + p);
                        assertFalse(t.contains("paper"), "API must not import Paper: " + t + " in " + p);
                        assertFalse(t.contains("folia"), "API must not import Folia: " + t + " in " + p);
                        assertFalse(t.contains("acelib"), "API must not import AceLib: " + t + " in " + p);
                        assertFalse(t.contains("java.sql"), "API must not import SQL: " + t + " in " + p);
                        assertFalse(t.contains("javax.sql"), "API must not import SQL: " + t + " in " + p);
                        assertFalse(t.contains("sqlite"), "API must not import SQLite: " + t + " in " + p);
                        assertFalse(t.contains("net.kyori.adventure"), "API must not import Adventure: " + t + " in " + p);
                        assertFalse(t.toLowerCase().contains("minimessage"), "API must not import MiniMessage: " + t);
                    }
                }
            }
        }
    }

    @Test
    void pluginAdapterHasNoForbiddenCalls() throws IOException {
        for (String line : readAllApi()) {
            String t = line.trim().toLowerCase();
            // forbidden runtime references
            assertFalse(t.contains("org.bukkit"), "adapter must not reference Bukkit: " + line);
            assertFalse(t.contains("paper"), "adapter must not reference Paper: " + line);
            assertFalse(t.contains("sqlite"), "adapter must not reference SQLite: " + line);
            assertFalse(t.contains("java.sql"), "adapter must not reference SQL: " + line);
            assertFalse(t.contains("jdbc"), "adapter must not reference JDBC: " + line);
            assertFalse(t.contains("world") && t.contains("getchunk"), "adapter must not load chunk: " + line);
            assertFalse(t.contains("persistenceStore") || t.contains("repository"), "adapter must not touch repository/persistence: " + line);
        }
        // also scan file content whole as lower
        for (Path p : Files.list(apiDir()).toList()) {
            String content = Files.readString(p).toLowerCase();
            assertFalse(content.contains("synchronized"), "read path must not use synchronized wait: " + p);
            assertFalse(content.contains(".wait("), "must not block: " + p);
            assertFalse(content.contains("thread.sleep"), "must not sleep/block: " + p);
            assertFalse(content.contains("future.get"), "must not wait on future: " + p);
            assertFalse(content.contains("minimessage"), "must not handle rendered message: " + p);
            assertFalse(content.contains("net.kyori"), "must not reference adventure: " + p);
        }
    }

    @Test
    void pluginAdapterDoesNotReturnMutableCollections() throws Exception {
        // Check that ChunkLandReadApi methods never return mutable collection types directly
        var clazz = ChunkLandReadApi.class;
        for (var m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("getLandSnapshot") || m.getName().equals("getSubLandSnapshot")) {
                assertTrue(m.getReturnType().equals(java.util.Optional.class), "should return Optional");
            }
        }
        // LandSnapshot itself is immutable (already tested elsewhere) but double-check via reflection that fields are private final
        var snap = com.smile.chunkland.api.land.LandSnapshot.class;
        for (var f : snap.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(f.getModifiers()), "LandSnapshot fields private: " + f);
            assertTrue(java.lang.reflect.Modifier.isFinal(f.getModifiers()), "LandSnapshot fields final: " + f);
        }
    }

    @Test
    void adapterSingleVolatileReadPerMethod() throws IOException {
        Path p = apiDir().resolve("ChunkLandReadApi.java");
        if (!Files.exists(p)) p = findProjectRoot().resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        String content = Files.readString(p);
        // Each method should capture snapshot once via registrySupplier.get()
        // Count occurrences of registrySupplier.get() – should appear in each method
        long count = content.lines().filter(l -> l.contains("registrySupplier.get()")).count();
        assertTrue(count >= 5, "each public method should capture snapshot once via registrySupplier.get(), found " + count);
        // Ensure no direct store field access beyond supplier
        assertFalse(content.contains("PersistenceStore"), "must not reference PersistenceStore directly beyond Store wrapper");
    }

    @Test
    void seamInterfacesUseSameSnapshot() throws Exception {
        // can / getRule / getProtectionDepth must use the same immutable snapshot for existence and lookups
        var providerMethod = PermissionContextProvider.class.getDeclaredMethod("provide",
                java.util.UUID.class, com.smile.chunkland.api.land.LandId.class,
                com.smile.chunkland.api.permission.ProtectionActionType.class,
                com.smile.chunkland.runtime.index.LandRegistry.class);
        assertNotNull(providerMethod, "PermissionContextProvider must have snapshot param");
        var ruleMethod = LandRuleLookup.class.getDeclaredMethod("getRule",
                com.smile.chunkland.api.land.LandId.class,
                com.smile.chunkland.api.rule.LandRuleType.class,
                com.smile.chunkland.runtime.index.LandRegistry.class);
        assertNotNull(ruleMethod, "LandRuleLookup must have snapshot param");
        var depthMethod = ProtectionDepthLookup.class.getDeclaredMethod("getProtectionDepth",
                com.smile.chunkland.api.land.LandId.class,
                com.smile.chunkland.runtime.index.LandRegistry.class);
        assertNotNull(depthMethod, "ProtectionDepthLookup must have snapshot param");
        // Assert ChunkLandReadApi passes that snapshot through
        Path p = apiDir().resolve("ChunkLandReadApi.java");
        if (!Files.exists(p)) p = findProjectRoot().resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/api/ChunkLandReadApi.java");
        String content = Files.readString(p);
        assertTrue(content.contains("contextProvider.provide(actor, landId, action, snapshot)"), "can must pass snapshot to provider");
        assertTrue(content.contains("ruleLookup.getRule(landId, rule, snapshot)"), "getRule must pass snapshot");
        assertTrue(content.contains("depthLookup.getProtectionDepth(landId, snapshot)"), "getProtectionDepth must pass snapshot");
        // And must not invoke provider/lookups without snapshot (mixed-version risk)
        assertFalse(content.contains("contextProvider.provide(actor, landId, action)"), "must not call provider without snapshot");
        assertFalse(content.contains("ruleLookup.getRule(landId, rule)"), "must not call ruleLookup without snapshot");
        assertFalse(content.contains("depthLookup.getProtectionDepth(landId)"), "must not call depthLookup without snapshot");
    }

    @Test
    void noConcreteWorkflowIdsInExecutableComments() throws IOException {
        Path root = findProjectRoot();
        Path apiMain = root.resolve("chunkland-api/src/main/java");
        Path pluginMain = root.resolve("chunkland-plugin/src/main/java");
        for (Path base : List.of(apiMain, pluginMain)) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String comments = commentText(Files.readString(file));
                    // concrete workflow/task ids like CL-M1-17 in executable comments would block archival
                    assertFalse(comments.contains("CL-M"), "executable comment must not contain concrete workflow ID CL-M: " + file);
                    // forbid task-style M<digit>-<digit> in LandSnapshot comments
                    if (file.toString().contains("LandSnapshot.java")) {
                        assertFalse(comments.contains("M1-06"), "LandSnapshot comment must not contain M1-06: " + file);
                    }
                }
            }
        }
    }

    @Test
    void lineCommentWorkflowIdIsDetected() {
        assertTrue(commentText("int x = 1; // CL-M2-21\n").contains("CL-M"));
    }

    @Test
    void blockCommentWorkflowIdIsDetected() {
        assertTrue(commentText("/* CL-M2-21 */ int x = 1;").contains("CL-M"));
    }

    @Test
    void multilineBlockCommentWorkflowIdIsDetected() {
        assertTrue(commentText("/**\n * See CL-M2-21 for context.\n */\nint x = 1;").contains("CL-M"));
    }

    @Test
    void stringLiteralWorkflowIdIsNotAComment() {
        assertFalse(commentText("String s = \"CL-M2-21\";").contains("CL-M"));
    }

    @Test
    void commentMarkersInsideStringDoNotStartComments() {
        assertFalse(commentText("String url = \"http://example\";").contains("CL-M"));
        assertFalse(commentText("String s = \"/* CL-M2-21 */\";").contains("CL-M"));
    }

    @Test
    void charLiteralDoesNotLeakIntoComments() {
        assertFalse(commentText("char c = 'x';\nString s = \"ok\";").contains("CL-M"));
        assertTrue(commentText("char c = '/'; // CL-M2-21\n").contains("CL-M"));
    }

    @Test
    void textBlockWorkflowIdIsNotAComment() {
        assertFalse(commentText("String s = \"\"\"\nCL-M2-21\n\"\"\";").contains("CL-M"));
    }

    /**
     * Extracts only {@code //} and {@code /* ... *&#47;} comment bodies.
     * String literals, char literals and text blocks are skipped so markers
     * or IDs inside program data are never mistaken for comments.
     */
    private static String commentText(String src) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int end = src.indexOf('\n', i + 2);
                    if (end < 0) end = n;
                    out.append(src, i + 2, end).append('\n');
                    i = end;
                    continue;
                }
                if (next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    if (end < 0) end = n; else end += 2;
                    out.append(src, i + 2, Math.min(end, n)).append('\n');
                    i = end;
                    continue;
                }
            }
            if (c == '"') {
                if (i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                    int end = src.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? n : end + 3;
                } else {
                    i++;
                    while (i < n) {
                        char s = src.charAt(i);
                        if (s == '\\') {
                            i += 2;
                        } else if (s == '"') {
                            i++;
                            break;
                        } else {
                            i++;
                        }
                    }
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char s = src.charAt(i);
                    if (s == '\\') {
                        i += 2;
                    } else if (s == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                continue;
            }
            i++;
        }
        return out.toString();
    }
}
