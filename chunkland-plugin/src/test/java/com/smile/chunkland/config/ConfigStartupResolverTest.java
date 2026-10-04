package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup policy for a missing or damaged {@code config.yml}.
 *
 * <p>Four explicit sources: {@code FILE_VALID} uses the file and refreshes
 * the last-known-good copy; {@code FIRST_INSTALL} seeds the shipped defaults
 * to disk exactly once; {@code MISSING_AFTER_USE} and {@code CORRUPT} fall
 * back to the last-known-good copy (or conservative in-memory defaults) and
 * never touch the shipped resource or the user's file.
 */
class ConfigStartupResolverTest {

    @TempDir
    Path temp;

    /** Strict operator config: full-height protection, strangers denied entry. */
    private static final String STRICT_YAML = ""
            + "worlds:\n"
            + "  world:\n"
            + "    claim-enabled: true\n"
            + "    vertical-mode: FULL_HEIGHT\n"
            + "subject-defaults:\n"
            + "  global:\n"
            + "    ENTRY: DENY\n";

    /** Loose shipped defaults: per-chunk depth, strangers allowed entry. */
    private static final String LOOSE_SEED_YAML = ""
            + "worlds:\n"
            + "  world:\n"
            + "    claim-enabled: true\n"
            + "    vertical-mode: PER_CHUNK_DEPTH\n"
            + "subject-defaults:\n"
            + "  global:\n"
            + "    ENTRY: ALLOW\n";

    private static final class RecordingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private record Fixture(Path dataFolder, Path configFile, Path lkgFile, Path databaseFile,
            RecordingHandler handler, Logger logger) {
    }

    private Fixture fixture() {
        Path dataFolder = temp.resolve("ChunkLand-" + UUID.randomUUID());
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("chunkland-config-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return new Fixture(dataFolder,
                dataFolder.resolve("config.yml"),
                dataFolder.resolve(ConfigStartupResolver.LAST_KNOWN_GOOD_FILE_NAME),
                dataFolder.resolve("chunkland.db"),
                handler, logger);
    }

    private ConfigStartupResolver.StartupResolution resolve(Fixture f,
            List<String> worlds) {
        return ConfigStartupResolver.resolve(f.configFile(), f.lkgFile(), f.databaseFile(),
                f.dataFolder(), worlds, () -> LOOSE_SEED_YAML,
                ConfigStartupResolver.StartupWriters.defaults(), f.logger());
    }

    private static long countLevel(Fixture f, Level level) {
        return f.handler().records.stream().filter(r -> r.getLevel().equals(level)).count();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<String> dirEntries(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256Hex(byte[] raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Test
    void firstInstallSeedsShippedDefaultsExactlyOnce() throws Exception {
        Fixture f = fixture();

        ConfigStartupResolver.StartupResolution first =
                resolve(f, List.of("world"));

        assertEquals(ConfigSource.FIRST_INSTALL, first.source());
        assertFalse(first.conservative(), "first install runs the shipped snapshot, not conservative");
        assertEquals(LOOSE_SEED_YAML, Files.readString(f.configFile(), StandardCharsets.UTF_8),
                "the shipped defaults must land on disk");
        assertEquals(VerticalMode.PER_CHUNK_DEPTH,
                first.snapshot().worlds().get("world").verticalMode());
        assertEquals(1, countLevel(f, Level.INFO), "first install logs exactly one INFO");
        assertEquals(0, countLevel(f, Level.SEVERE), "first install logs no ERROR");
        assertEquals(0, countLevel(f, Level.WARNING), "successful landing logs no WARNING");

        Fixture second = new Fixture(f.dataFolder(), f.configFile(), f.lkgFile(), f.databaseFile(),
                new RecordingHandler(), Logger.getLogger("chunkland-config-test-" + UUID.randomUUID()));
        second.logger().setUseParentHandlers(false);
        second.logger().addHandler(second.handler());
        ConfigStartupResolver.StartupResolution again = ConfigStartupResolver.resolve(
                second.configFile(), second.lkgFile(), second.databaseFile(), second.dataFolder(),
                List.of("world"), () -> LOOSE_SEED_YAML,
                ConfigStartupResolver.StartupWriters.defaults(), second.logger());

        assertEquals(ConfigSource.FILE_VALID, again.source(),
                "the landed file must load as a normal valid config afterwards");
        assertEquals(0, countLevel(second, Level.INFO),
                "a valid load stays quiet");
    }

    @Test
    void missingAfterUseFallsBackToLkgWithoutSeeding() throws Exception {
        Fixture f = fixture();
        // Existing state (a non-empty database) proves this is not a first install.
        write(f.databaseFile(), "not-empty");
        write(f.lkgFile(), STRICT_YAML);

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.MISSING_AFTER_USE, resolution.source());
        assertFalse(Files.exists(f.configFile()),
                "a missing-after-use start must never land the shipped defaults");
        assertEquals(VerticalMode.FULL_HEIGHT,
                resolution.snapshot().worlds().get("world").verticalMode());
        assertEquals(PermissionState.DENY,
                resolution.snapshot().subjectDefaults().global().get(ProtectionActionType.ENTRY));
        assertEquals(1, countLevel(f, Level.SEVERE), "missing-after-use logs exactly one ERROR");
        String message = f.handler().records.get(0).getMessage();
        assertTrue(message.contains(f.configFile().toAbsolutePath().toString()),
                "the ERROR must name the missing file: " + message);
    }

    @Test
    void missingAfterUseWithoutLkgIsConservative() throws Exception {
        Fixture f = fixture();
        write(f.databaseFile(), "not-empty");

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.MISSING_AFTER_USE, resolution.source());
        assertTrue(resolution.conservative());
        assertFalse(Files.exists(f.configFile()), "shipped defaults must not land");
        assertNull(resolution.snapshot().economy(), "conservative pricing stays unavailable");
        assertEquals(1, countLevel(f, Level.SEVERE));
    }

    @Test
    void corruptNeverUsesShippedResourceAndLeavesOriginalAlone() throws Exception {
        Fixture f = fixture();
        write(f.configFile(), STRICT_YAML);
        byte[] before = Files.readAllBytes(f.configFile());
        // Break the file the way a bad operator edit would: an illegal value
        // under a known key, so the failure names a dotted key path.
        write(f.configFile(), STRICT_YAML.replace("FULL_HEIGHT", "SIDEWAYS"));
        byte[] damaged = Files.readAllBytes(f.configFile());
        String damagedSha = sha256Hex(damaged);
        FileTime damagedMtime = Files.getLastModifiedTime(f.configFile());

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.CORRUPT, resolution.source());
        assertTrue(resolution.conservative(), "no LKG exists, so the fallback is conservative");
        assertNotEquals(VerticalMode.PER_CHUNK_DEPTH,
                resolution.snapshot().worlds().get("world").verticalMode(),
                "protection depth must not silently drop to the shipped PER_CHUNK_DEPTH");
        assertEquals(VerticalMode.FULL_HEIGHT,
                resolution.snapshot().worlds().get("world").verticalMode());
        assertNotEquals(PermissionState.ALLOW,
                resolution.snapshot().subjectDefaults().global()
                        .getOrDefault(ProtectionActionType.ENTRY, PermissionState.INHERIT),
                "ENTRY must not silently rise to the shipped ALLOW");
        assertNull(resolution.snapshot().economy());
        assertEquals(PermissionState.DENY, resolution.snapshot().ruleDefaults().global()
                .get(LandRuleType.PASSIVE_MOB_SPAWN),
                "the built-in ALLOW rule must be pinned to DENY in conservative mode");
        assertEquals(List.of("config.yml"), dirEntries(f.dataFolder()),
                "a corrupt start must not write, rename or add any file next to the original");
        assertEquals(damagedSha, sha256Hex(Files.readAllBytes(f.configFile())),
                "the damaged original must be byte-identical after a corrupt start");
        assertEquals(damagedMtime, Files.getLastModifiedTime(f.configFile()),
                "the damaged original's mtime must be untouched by a corrupt start");
        assertEquals(1, countLevel(f, Level.SEVERE), "corrupt start logs exactly one ERROR");
        String message = f.handler().records.get(0).getMessage();
        assertTrue(message.contains(f.configFile().toAbsolutePath().toString()),
                "the ERROR must name the damaged file: " + message);
        assertTrue(message.contains("worlds.world.vertical-mode"),
                "the ERROR must name the failing key path: " + message);
        // The damaged bytes are the operator's evidence; the resolver only reads them.
        assertFalse(java.util.Arrays.equals(before, Files.readAllBytes(f.configFile())),
                "precondition: the test really did damage the file");
    }

    @Test
    void corruptWithLkgRestoresLastKnownGood() throws Exception {
        Fixture f = fixture();
        write(f.configFile(), STRICT_YAML);
        write(f.lkgFile(), STRICT_YAML);
        write(f.configFile(), "worlds:\n  world:\n    claim-enabled: oops\n");

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.CORRUPT, resolution.source());
        assertFalse(resolution.conservative(), "the LKG snapshot is the original policy, not conservative");
        assertEquals(VerticalMode.FULL_HEIGHT,
                resolution.snapshot().worlds().get("world").verticalMode());
        assertEquals(PermissionState.DENY,
                resolution.snapshot().subjectDefaults().global().get(ProtectionActionType.ENTRY));
        assertEquals(1, countLevel(f, Level.SEVERE));
        String message = f.handler().records.get(0).getMessage();
        assertTrue(message.contains(f.configFile().toAbsolutePath().toString()), message);
        assertTrue(message.contains("claim-enabled"), message);
    }

    @Test
    void strayFileInDataFolderDefeatsFirstInstall() throws Exception {
        Fixture f = fixture();
        write(f.dataFolder().resolve("playerdata.bin"), "traces");

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.MISSING_AFTER_USE, resolution.source(),
                "any leftover trace means someone used this folder before");
        assertFalse(Files.exists(f.configFile()));
    }

    @Test
    void emptyDatabaseStillCountsAsFirstInstall() throws Exception {
        Fixture f = fixture();
        write(f.databaseFile(), "");

        ConfigStartupResolver.StartupResolution resolution = resolve(f, List.of("world"));

        assertEquals(ConfigSource.FIRST_INSTALL, resolution.source(),
                "a zero-byte database is a fresh open, not existing state");
    }

    @Test
    void lkgWriterRunsOnlyOnSuccessfulLoad() throws Exception {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> written = new AtomicReference<>();
        ConfigStartupResolver.StartupWriters writers =
                new ConfigStartupResolver.StartupWriters(
                        (target, text) -> {
                            calls.incrementAndGet();
                            written.set(text);
                        },
                        ConfigStartupResolver::writeLanding);

        write(f.configFile(), STRICT_YAML);
        ConfigStartupResolver.StartupResolution ok = ConfigStartupResolver.resolve(
                f.configFile(), f.lkgFile(), f.databaseFile(), f.dataFolder(), List.of("world"),
                () -> LOOSE_SEED_YAML, writers, f.logger());
        assertEquals(ConfigSource.FILE_VALID, ok.source());
        assertEquals(1, calls.get(), "a valid load refreshes the LKG copy once");
        assertEquals(STRICT_YAML, written.get(), "the LKG copy is the validated raw YAML");

        write(f.configFile(), "worlds:\n  world:\n    claim-enabled: oops\n");
        ConfigStartupResolver.StartupResolution broken = ConfigStartupResolver.resolve(
                f.configFile(), f.lkgFile(), f.databaseFile(), f.dataFolder(), List.of("world"),
                () -> LOOSE_SEED_YAML, writers, f.logger());
        assertEquals(ConfigSource.CORRUPT, broken.source());
        assertEquals(1, calls.get(), "a corrupt load must never refresh the LKG copy");
    }

    @Test
    void lkgWriteFailureWarnsButKeepsValidSnapshot() throws Exception {
        Fixture f = fixture();
        ConfigStartupResolver.StartupWriters writers =
                new ConfigStartupResolver.StartupWriters(
                        (target, text) -> {
                            throw new IOException("disk full");
                        },
                        ConfigStartupResolver::writeLanding);
        write(f.configFile(), STRICT_YAML);

        ConfigStartupResolver.StartupResolution resolution = ConfigStartupResolver.resolve(
                f.configFile(), f.lkgFile(), f.databaseFile(), f.dataFolder(), List.of("world"),
                () -> LOOSE_SEED_YAML, writers, f.logger());

        assertEquals(ConfigSource.FILE_VALID, resolution.source());
        assertEquals(VerticalMode.FULL_HEIGHT,
                resolution.snapshot().worlds().get("world").verticalMode());
        assertEquals(1, countLevel(f, Level.WARNING));
        assertEquals(0, countLevel(f, Level.SEVERE));
        assertNotNull(resolution.reloadLoader());
    }

    @Test
    void landingFailureWarnsAndKeepsSeedSnapshotInMemory() throws Exception {
        Fixture f = fixture();
        ConfigStartupResolver.StartupWriters writers =
                new ConfigStartupResolver.StartupWriters(
                        ConfigStartupResolver::writeLkgAtomically,
                        (target, text) -> {
                            throw new IOException("read-only media");
                        });

        ConfigStartupResolver.StartupResolution resolution = ConfigStartupResolver.resolve(
                f.configFile(), f.lkgFile(), f.databaseFile(), f.dataFolder(), List.of("world"),
                () -> LOOSE_SEED_YAML, writers, f.logger());

        assertEquals(ConfigSource.FIRST_INSTALL, resolution.source());
        assertFalse(Files.exists(f.configFile()), "a failed landing must not half-write");
        assertEquals(VerticalMode.PER_CHUNK_DEPTH,
                resolution.snapshot().worlds().get("world").verticalMode(),
                "enable continues with the in-memory shipped snapshot");
        assertEquals(1, countLevel(f, Level.WARNING), "landing failure degrades to WARNING");
        assertEquals(0, countLevel(f, Level.SEVERE));
    }
}
