package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RejectionMessagingStructureTest {

    private static Path findModuleDir() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(cur.resolve("chunkland-plugin"))
                    && Files.isDirectory(cur.resolve("chunkland-api"))) {
                return cur.resolve("chunkland-plugin");
            }
            Path parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        return Paths.get("chunkland-plugin");
    }

    private static String readListener() throws IOException {
        Path file = findModuleDir().resolve(
                "src/main/java/com/smile/chunkland/protection/ProtectionListener.java");
        assertTrue(Files.isRegularFile(file), "ProtectionListener must exist: " + file);
        return Files.readString(file);
    }

    private static List<Path> rejectionSources() throws IOException {
        Path dir = findModuleDir().resolve(
                "src/main/java/com/smile/chunkland/message/rejection");
        assertTrue(Files.isDirectory(dir), "rejection package must exist: " + dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void listenerHooksRejectionThroughNotifierSeamOnly() throws IOException {
        String src = readListener();
        assertTrue(src.contains("RejectionNotifier"),
                "DENY branches must notify through the rejection seam");
        assertFalse(src.contains("ChunkLandMessagePipeline"),
                "listener must not touch the pipeline directly; only the notifier seam");
        assertFalse(src.contains("net.kyori"),
                "listener must not build Adventure Components; rendering stays lazy in rejection");
        assertFalse(src.contains("MiniMessage"),
                "listener must not touch MiniMessage; rendering stays lazy in rejection");
    }

    @Test
    void listenerNeverSleepsForCooldown() throws IOException {
        String src = readListener();
        assertFalse(src.contains("Thread.sleep"),
                "cooldown uses an injected clock, never sleeps on the event thread");
        for (Path file : rejectionSources()) {
            String content = Files.readString(file);
            assertFalse(content.contains("Thread.sleep"),
                    "rejection must not sleep: " + file.getFileName());
            assertFalse(content.contains("CL-M"),
                    "executable source must not carry concrete workflow IDs: "
                            + file.getFileName());
        }
    }

    @Test
    void rejectionTemplateExistsInBothLocales() throws Exception {
        for (String locale : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File(findModuleDir()
                    + "/src/main/resources/lang/" + locale + ".yml"));
            String template = cfg.getString("protection.rejection.action_denied");
            assertNotNull(template, locale + " missing protection.rejection.action_denied");
            assertTrue(template.contains("<action>"),
                    locale + " template must name the action");
            assertTrue(template.contains("<reason>"),
                    locale + " template must carry the reason");
        }
    }

    @Test
    void bannedInsideUsesStableMarkerNeverRawEnglish() throws IOException {
        String src = readListener();
        assertTrue(src.contains("BANNED_INSIDE_REASON"),
                "ban-inside notice must carry the stable marker constant");
        assertFalse(src.contains("Banned inside this land"),
                "raw English reason must never be composed for the player");
    }

    @Test
    void entryTemplatesExistInBothLocales() throws Exception {
        for (String locale : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File(findModuleDir()
                    + "/src/main/resources/lang/" + locale + ".yml"));
            assertNotNull(cfg.getString("protection.rejection.entry_denied"),
                    locale + " missing protection.rejection.entry_denied");
            assertNotNull(cfg.getString("protection.rejection.banned_inside"),
                    locale + " missing protection.rejection.banned_inside");
        }
    }

    @Test
    void orphanKeysStayRemoved() throws Exception {
        String[] orphans = {
            "protection.rejection.denied",
            "protection.rejection.limit",
            "protection.rejection.cooldown",
            "protection.rejection.economy",
            "protection.allow.silent",
        };
        for (String locale : new String[]{"en_US", "zh_TW"}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(new File(findModuleDir()
                    + "/src/main/resources/lang/" + locale + ".yml"));
            for (String key : orphans) {
                assertNull(cfg.getString(key), locale + " must not carry orphan key " + key);
            }
        }
    }
}
