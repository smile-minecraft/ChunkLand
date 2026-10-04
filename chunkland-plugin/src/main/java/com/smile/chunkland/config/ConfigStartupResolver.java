package com.smile.chunkland.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * Startup policy for a missing or damaged {@code config.yml}.
 *
 * <p>Resolution picks one of the four {@link ConfigSource} outcomes and the
 * snapshot that goes with it. The policy never gets looser than the
 * operator's own last validated file: a damaged file falls back to the
 * last-known-good copy (or conservative in-memory defaults), never to the
 * shipped resource, and the operator's file is only ever read — never
 * overwritten, deleted or renamed. Recovery is always "fix the file and
 * restart"; no reload command is involved.
 *
 * <p>The resolver is Bukkit-free on purpose: every environment input (paths,
 * server world names, shipped text, writers, logger) is a parameter, so the
 * whole policy is unit-testable over a temporary directory. Production calls
 * it once from {@code onEnable}, before the persistence bootstrap opens or
 * creates the database — that ordering is what keeps a first install
 * distinguishable from a deleted config. File writes here are one-shot
 * startup I/O, never protection hot-path I/O.
 */
public final class ConfigStartupResolver {

    /** File name of the last-known-good copy inside the plugin data folder. */
    public static final String LAST_KNOWN_GOOD_FILE_NAME = "config-last-known-good.yml";

    /** Resolved startup: where the snapshot came from and how reloads read. */
    public record StartupResolution(
            ConfigSource source,
            ChunkLandConfig snapshot,
            ConfigLoader reloadLoader,
            boolean conservative) {
        public StartupResolution {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(reloadLoader, "reloadLoader");
        }
    }

    /** Supplies the shipped default text (production reads {@code /config.yml}). */
    @FunctionalInterface
    public interface SeedSupplier {
        String seedYaml() throws IOException;
    }

    /** Writes one text file; production uses atomic writes, tests inject faults. */
    @FunctionalInterface
    public interface TextFileWriter {
        void write(Path target, String text) throws IOException;
    }

    /** The two file writes resolution may perform, bundled for injection. */
    public record StartupWriters(TextFileWriter lkg, TextFileWriter landing) {
        public StartupWriters {
            Objects.requireNonNull(lkg, "lkg");
            Objects.requireNonNull(landing, "landing");
        }

        public static StartupWriters defaults() {
            return new StartupWriters(
                    ConfigStartupResolver::writeLkgAtomically,
                    ConfigStartupResolver::writeLanding);
        }
    }

    private ConfigStartupResolver() {
        // utility class
    }

    /**
     * Refresh the last-known-good copy: temp file plus atomic move in the
     * same directory, so a crash mid-write never leaves a half-written copy.
     */
    public static void writeLkgAtomically(Path lkgFile, String text) throws IOException {
        Objects.requireNonNull(lkgFile, "lkgFile");
        Objects.requireNonNull(text, "text");
        Path parent = lkgFile.toAbsolutePath().getParent();
        Path dir = parent == null ? Path.of(".") : parent;
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".config-lkg-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, lkgFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException retryPlain) {
                Files.move(tmp, lkgFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // The temp file is best-effort cleanup; the original failure matters.
            }
            throw failure;
        }
    }

    /**
     * Land the shipped defaults as {@code config.yml}, creating it only.
     * An already-present file fails with {@code FileAlreadyExistsException}
     * instead of being overwritten: nothing is ever clobbered. The caller
     * keeps the already-parsed in-memory seed snapshot (plus one WARNING) and
     * does not re-read, so a first install still enables exactly once; the
     * on-disk content is left for the next restart to load. In practice the
     * earlier existence check runs on the same startup thread, so a present
     * file here only means something landed it concurrently.
     */
    public static void writeLanding(Path configFile, String text) throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(text, "text");
        Path parent = configFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Resolve the startup source and snapshot. Never throws: every I/O or
     * parse failure degrades to a fallback snapshot plus a single log record.
     *
     * @param configFile   operator file (read-only for this method, except a
     *                     first-install landing)
     * @param lkgFile      last-known-good copy (refreshed only on valid loads)
     * @param databaseFile persistence file, only stat'ed, never opened here
     * @param dataFolder   plugin data folder, only listed for leftover traces
     * @param serverWorldNames world names at startup for the conservative map;
     *                     {@code null} behaves like empty
     * @param seed         shipped default text, read only on first install
     * @param writers      file-write seams
     * @param logger       single-record alert sink
     */
    public static StartupResolution resolve(
            Path configFile,
            Path lkgFile,
            Path databaseFile,
            Path dataFolder,
            Collection<String> serverWorldNames,
            SeedSupplier seed,
            StartupWriters writers,
            Logger logger) {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(lkgFile, "lkgFile");
        Objects.requireNonNull(databaseFile, "databaseFile");
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(writers, "writers");
        Objects.requireNonNull(logger, "logger");
        ConfigLoader reloadLoader = new YamlFileConfigLoader(configFile);
        try {
            return resolveOrThrow(configFile, lkgFile, databaseFile, dataFolder,
                    serverWorldNames, seed, writers, logger, reloadLoader);
        } catch (Exception unexpected) {
            logger.severe("ChunkLand config bootstrap hit an unexpected error (" + unexpected
                    + "); starting with conservative in-memory defaults "
                    + "(all worlds unclaimable full-height protection, pricing unavailable). "
                    + "Fix the data folder and restart the server. source=CORRUPT.");
            return new StartupResolution(ConfigSource.CORRUPT,
                    ConservativeConfigFactory.forWorlds(serverWorldNames),
                    reloadLoader, true);
        }
    }

    private static StartupResolution resolveOrThrow(
            Path configFile,
            Path lkgFile,
            Path databaseFile,
            Path dataFolder,
            Collection<String> serverWorldNames,
            SeedSupplier seed,
            StartupWriters writers,
            Logger logger,
            ConfigLoader reloadLoader) throws IOException {
        if (Files.isRegularFile(configFile)) {
            String raw;
            try {
                raw = Files.readString(configFile, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                return corrupt(configFile, lkgFile, serverWorldNames, logger, reloadLoader,
                        "the file exists but cannot be read (" + unreadable.getMessage() + ")");
            }
            ChunkLandConfig parsed;
            try {
                parsed = parse(raw);
            } catch (RuntimeException invalid) {
                return corrupt(configFile, lkgFile, serverWorldNames, logger, reloadLoader,
                        invalid.getMessage());
            }
            try {
                writers.lkg().write(lkgFile, raw);
            } catch (IOException | RuntimeException lkgFailure) {
                logger.warning("ChunkLand config loaded, but the last-known-good copy ("
                        + lkgFile.toAbsolutePath() + ") could not be refreshed ("
                        + lkgFailure.getMessage()
                        + "); continuing with the validated file snapshot. source=FILE_VALID.");
            }
            return new StartupResolution(ConfigSource.FILE_VALID, parsed, reloadLoader, false);
        }
        if (!isFirstInstall(lkgFile, databaseFile, dataFolder, configFile)) {
            return missingAfterUse(configFile, lkgFile, serverWorldNames, logger, reloadLoader);
        }
        String seedYaml;
        try {
            seedYaml = seed.seedYaml();
        } catch (IOException | RuntimeException seedFailure) {
            logger.warning("ChunkLand first install: the shipped defaults are unreadable ("
                    + seedFailure.getMessage()
                    + "); starting with conservative in-memory defaults. source=FIRST_INSTALL.");
            return new StartupResolution(ConfigSource.FIRST_INSTALL,
                    ConservativeConfigFactory.forWorlds(serverWorldNames), reloadLoader, true);
        }
        ChunkLandConfig seeded;
        try {
            seeded = parse(seedYaml);
        } catch (RuntimeException seedInvalid) {
            logger.warning("ChunkLand first install: the shipped defaults do not parse ("
                    + seedInvalid.getMessage()
                    + "); starting with conservative in-memory defaults. source=FIRST_INSTALL.");
            return new StartupResolution(ConfigSource.FIRST_INSTALL,
                    ConservativeConfigFactory.forWorlds(serverWorldNames), reloadLoader, true);
        }
        try {
            writers.landing().write(configFile, seedYaml);
        } catch (IOException | RuntimeException landingFailure) {
            logger.warning("ChunkLand first install: the shipped defaults could not be written to "
                    + configFile.toAbsolutePath() + " (" + landingFailure.getMessage()
                    + "); continuing with the in-memory shipped snapshot without retrying. "
                    + "source=FIRST_INSTALL.");
            return new StartupResolution(ConfigSource.FIRST_INSTALL, seeded, reloadLoader, false);
        }
        logger.info("ChunkLand first install: no config.yml found, so the shipped defaults were "
                + "written to " + configFile.toAbsolutePath()
                + " (once). Edit the file to taste and restart the server. source=FIRST_INSTALL.");
        return new StartupResolution(ConfigSource.FIRST_INSTALL, seeded, reloadLoader, false);
    }

    private static StartupResolution corrupt(
            Path configFile,
            Path lkgFile,
            Collection<String> serverWorldNames,
            Logger logger,
            ConfigLoader reloadLoader,
            String reason) {
        LastKnownGood lkg = loadLkg(lkgFile);
        if (lkg.snapshot() != null) {
            logger.severe("ChunkLand config is damaged: " + configFile.toAbsolutePath()
                    + " failed to load (" + reason + "). Falling back to " + lkg.detail()
                    + "; shipped defaults were NOT used. "
                    + "Fix the file and restart the server. source=CORRUPT.");
            return new StartupResolution(ConfigSource.CORRUPT, lkg.snapshot(), reloadLoader, false);
        }
        logger.severe("ChunkLand config is damaged: " + configFile.toAbsolutePath()
                + " failed to load (" + reason + "). " + lkg.detail()
                + ". Falling back to conservative in-memory defaults "
                + "(all worlds unclaimable full-height protection, pricing unavailable); "
                + "shipped defaults were NOT used. "
                + "Fix the file and restart the server. source=CORRUPT.");
        return new StartupResolution(ConfigSource.CORRUPT,
                ConservativeConfigFactory.forWorlds(serverWorldNames), reloadLoader, true);
    }

    private static StartupResolution missingAfterUse(
            Path configFile,
            Path lkgFile,
            Collection<String> serverWorldNames,
            Logger logger,
            ConfigLoader reloadLoader) {
        LastKnownGood lkg = loadLkg(lkgFile);
        if (lkg.snapshot() != null) {
            logger.severe("ChunkLand config is missing: " + configFile.toAbsolutePath()
                    + " was not found, but previous state exists. Falling back to "
                    + lkg.detail() + "; shipped defaults were NOT written. "
                    + "Restore the file and restart the server. source=MISSING_AFTER_USE.");
            return new StartupResolution(
                    ConfigSource.MISSING_AFTER_USE, lkg.snapshot(), reloadLoader, false);
        }
        logger.severe("ChunkLand config is missing: " + configFile.toAbsolutePath()
                + " was not found, but previous state exists. " + lkg.detail()
                + ". Falling back to conservative in-memory defaults "
                + "(all worlds unclaimable full-height protection, pricing unavailable); "
                + "shipped defaults were NOT written. "
                + "Restore the file and restart the server. source=MISSING_AFTER_USE.");
        return new StartupResolution(ConfigSource.MISSING_AFTER_USE,
                ConservativeConfigFactory.forWorlds(serverWorldNames), reloadLoader, true);
    }

    private static ChunkLandConfig parse(String yaml) {
        return ConfigSchema.parseAndValidate(new Yaml().load(yaml));
    }

    /**
     * First install means no last-known-good copy, no database content, and
     * no other leftover in the data folder. Any stat or listing failure
     * counts as "traces exist": misclassifying towards {@code
     * MISSING_AFTER_USE} (error plus fallback) is safer than seeding shipped
     * defaults over an established server. A missing data folder simply has
     * no traces.
     */
    private static boolean isFirstInstall(
            Path lkgFile, Path databaseFile, Path dataFolder, Path configFile) {
        if (Files.isRegularFile(lkgFile)) {
            return false;
        }
        try {
            if (Files.isRegularFile(databaseFile) && Files.size(databaseFile) > 0) {
                return false;
            }
        } catch (IOException | RuntimeException statFailure) {
            return false;
        }
        Path lkgAbs = lkgFile.toAbsolutePath().normalize();
        Path dbAbs = databaseFile.toAbsolutePath().normalize();
        Path configAbs = configFile.toAbsolutePath().normalize();
        try (Stream<Path> entries = Files.list(dataFolder)) {
            return entries.map(p -> p.toAbsolutePath().normalize()).noneMatch(
                    p -> !p.equals(lkgAbs) && !p.equals(dbAbs) && !p.equals(configAbs));
        } catch (NoSuchFileException missingDir) {
            return true;
        } catch (IOException | RuntimeException listFailure) {
            return false;
        }
    }

    private record LastKnownGood(ChunkLandConfig snapshot, String detail) {
    }

    private static LastKnownGood loadLkg(Path lkgFile) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(lkgFile);
        } catch (IOException | RuntimeException unreadable) {
            return new LastKnownGood(null, "no last-known-good copy is readable");
        }
        ChunkLandConfig parsed;
        try {
            parsed = parse(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            return new LastKnownGood(null, "the last-known-good copy is itself invalid");
        }
        return new LastKnownGood(parsed, "the last-known-good copy (" + describeLkg(lkgFile, raw) + ")");
    }

    private static String describeLkg(Path lkgFile, byte[] raw) {
        String time;
        try {
            time = "written " + Files.getLastModifiedTime(lkgFile).toString();
        } catch (IOException | RuntimeException statFailure) {
            time = "write time unavailable";
        }
        return time + ", sha256 " + sha256Hex(raw);
    }

    private static String sha256Hex(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must exist", impossible);
        }
    }
}
