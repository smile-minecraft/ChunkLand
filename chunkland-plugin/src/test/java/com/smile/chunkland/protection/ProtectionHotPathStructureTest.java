package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the protection hot path against persistence, economy, world-loading,
 * and blocking references.
 *
 * <p>Scope covers every file the event thread executes: the
 * {@code protection} package itself, the rejection seam
 * ({@code message/rejection}), and the environment-rule lookup
 * ({@code runtime/rule}). All checks run through the shared
 * {@link HotPathStructure} scanner; the render profile (no
 * Adventure/MiniMessage on the event thread) applies only to the protection
 * package, because the rejection renderer builds its component lazily and
 * only for a real send.
 */
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

    private static Path packageDir(String packagePath) {
        Path root = findProjectRoot();
        Path cand = root.resolve(
                "chunkland-plugin/src/main/java/" + packagePath);
        if (Files.isDirectory(cand)) {
            return cand;
        }
        Path fallback = Paths.get("chunkland-plugin/src/main/java/" + packagePath);
        if (Files.isDirectory(fallback)) {
            return fallback;
        }
        return cand;
    }

    private static List<Path> protectionSources() throws IOException {
        Path dir = packageDir("com/smile/chunkland/protection");
        assertTrue(Files.isDirectory(dir), "protection package must exist: " + dir);
        return HotPathStructure.javaSourcesUnder(dir);
    }

    private static List<Path> hotPathSources() throws IOException {
        List<Path> all = new ArrayList<>(protectionSources());
        for (String pkg : List.of(
                "com/smile/chunkland/message/rejection",
                "com/smile/chunkland/runtime/rule")) {
            Path dir = packageDir(pkg);
            assertTrue(Files.isDirectory(dir), "hot-path package must exist: " + dir);
            all.addAll(HotPathStructure.javaSourcesUnder(dir));
        }
        return all;
    }

    @Test
    void hotPathTouchesNoStorageOrEconomyOrChunkLoading() throws IOException {
        List<String> violations = HotPathStructure.scanHotPath(hotPathSources());
        assertTrue(violations.isEmpty(),
                "hot path must stay memory-only, violations: " + violations);
    }

    @Test
    void protectionPackageRendersNoMessages() throws IOException {
        List<String> violations = HotPathStructure.scanRender(protectionSources());
        assertTrue(violations.isEmpty(),
                "protection package must not render messages: " + violations);
    }

    @Test
    void scannerCatchesPlantedViolations() {
        List<String> planted = List.of(
                "import java.sql.Connection;",
                "Class.forName(\"org.sqlite.JDBC\");",
                "var conn = (java.sql.Connection) dataSource.getConnection();",
                "economy.withdraw(player, amount);",
                "vaultHook.deposit(player, reward);",
                "world.loadChunk(chunkX, chunkZ);",
                "World world = block.getWorld(); Chunk c = world.getChunkAt(x, z);",
                "Thread.sleep(50);",
                "synchronized (lock) { counter++; }",
                "try { future.get(); } catch (Exception ignored) {}",
                "player.wait(100);",
                "Component msg = MiniMessage.miniMessage().deserialize(raw);",
                "net.kyori.adventure.text.Component text = null;");
        List<String> missed = new ArrayList<>();
        for (String line : planted) {
            boolean hot = HotPathStructure.hotPathViolation(line).isPresent();
            boolean render = HotPathStructure.renderViolation(line).isPresent();
            if (!hot && !render) {
                missed.add(line);
            }
        }
        assertTrue(missed.isEmpty(),
                "scanner must catch every planted violation, missed: " + missed);

        List<String> clean = List.of(
                "import java.util.concurrent.ConcurrentHashMap;",
                "LandRegistry snapshot = registrySupplier.get();",
                "int chunkX = block.getX() >> 4;",
                "cooldown.tryAcquire(playerId, action);",
                "return PermissionResolver.resolve(ctx);");
        List<String> falsePositives = new ArrayList<>();
        for (String line : clean) {
            if (HotPathStructure.hotPathViolation(line).isPresent()
                    || HotPathStructure.renderViolation(line).isPresent()) {
                falsePositives.add(line);
            }
        }
        assertTrue(falsePositives.isEmpty(),
                "scanner must not flag ordinary hot-path code: " + falsePositives);
    }

    @Test
    void hotPathScanCoversRejectionAndRule() {
        Path root = findProjectRoot();
        List<String> expected = List.of(
                "chunkland-plugin/src/main/java/com/smile/chunkland/message/rejection/RejectionNotifier.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/message/rejection/RejectionCooldown.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/message/rejection/PipelineRejectionRenderer.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/runtime/rule/LandRuleService.java");
        List<String> missing = new ArrayList<>();
        for (String rel : expected) {
            assertTrue(Files.isRegularFile(root.resolve(rel)), "hot-path file must exist: " + rel);
        }
        List<String> scanned;
        try {
            scanned = hotPathSources().stream().map(Path::toString).toList();
        } catch (IOException ex) {
            throw new AssertionError("hot-path scan must be readable", ex);
        }
        for (String rel : expected) {
            String tail = rel.substring(rel.indexOf("com/smile"));
            boolean covered = scanned.stream().anyMatch(p -> p.replace('\\', '/').endsWith(tail));
            if (!covered) {
                missing.add(rel);
            }
        }
        assertTrue(missing.isEmpty(),
                "hot-path scan must cover rejection + rule files, missing: " + missing);
    }

    @Test
    void engineRegistryAndRuleServiceStayBukkitFree() throws IOException {
        for (String name : List.of(
                "com/smile/chunkland/protection/ProtectionEngine.java",
                "com/smile/chunkland/protection/ProtectionActionRegistry.java",
                "com/smile/chunkland/runtime/rule/LandRuleService.java")) {
            Path file = packageDir(name.substring(0, name.lastIndexOf('/')))
                    .resolve(name.substring(name.lastIndexOf('/') + 1));
            assertTrue(Files.exists(file), "missing hot-path source: " + name);
            String src = Files.readString(file);
            assertFalse(src.contains("org.bukkit"),
                    name + " must stay Bukkit-free so region threads only read snapshots");
            assertFalse(src.contains("acelib"),
                    name + " must not depend on AceLib");
        }
    }

    @Test
    void noConcreteWorkflowIdsInHotPathSources() throws IOException {
        for (Path file : hotPathSources()) {
            String src = Files.readString(file);
            assertFalse(src.contains("CL-M"),
                    "executable source must not carry concrete workflow IDs: " + file);
        }
    }
}
