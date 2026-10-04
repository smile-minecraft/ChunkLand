package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Missing-vs-invalid split for the file-backed loader.
 *
 * <p>A missing {@code config.yml} must surface as a dedicated signal instead
 * of silently succeeding with schema defaults: at startup the two cases pick
 * different policies (first install vs corrupt), and at reload a deleted file
 * must keep the previous snapshot instead of publishing defaults over a
 * stricter live config.
 */
class YamlFileConfigLoaderTest {

    @TempDir
    Path temp;

    private static final String STRICT_YAML = ""
            + "worlds:\n"
            + "  world:\n"
            + "    claim-enabled: true\n"
            + "    vertical-mode: FULL_HEIGHT\n"
            + "subject-defaults:\n"
            + "  global:\n"
            + "    ENTRY: DENY\n";

    @Test
    void missingFileThrowsDedicatedSignalInsteadOfDefaults() {
        Path missing = temp.resolve("config.yml");
        YamlFileConfigLoader loader = new YamlFileConfigLoader(missing);

        ConfigMissingException missingSignal = assertThrows(
                ConfigMissingException.class, loader::load,
                "a missing file must throw, not return schema defaults");
        assertTrue(missingSignal.getMessage().contains(missing.toAbsolutePath().toString()),
                "the signal must name the file: " + missingSignal.getMessage());
    }

    @Test
    void invalidFileStillThrowsValidation() throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, "worlds:\n  world:\n    claim-enabled: oops\n",
                StandardCharsets.UTF_8);

        assertThrows(ConfigValidationException.class,
                () -> new YamlFileConfigLoader(file).load());
    }

    @Test
    void validFileParsesToTypedSnapshot() throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, STRICT_YAML, StandardCharsets.UTF_8);

        ChunkLandConfig snapshot = new YamlFileConfigLoader(file).load();
        assertEquals(VerticalMode.FULL_HEIGHT, snapshot.worlds().get("world").verticalMode());
    }

    @Test
    void reloadAfterFileDeletionKeepsPreviousSnapshot() throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, STRICT_YAML, StandardCharsets.UTF_8);
        ConfigService service = new ConfigService(new YamlFileConfigLoader(file));
        ChunkLandConfig before = service.current();

        Files.delete(file);

        ChunkLandConfig after = service.reload();
        assertSame(before, after,
                "a deleted file must not publish defaults over the live snapshot");
        assertEquals(0L, after.globalPolicyEpoch());
        assertEquals(VerticalMode.FULL_HEIGHT, after.worlds().get("world").verticalMode());
    }
}
