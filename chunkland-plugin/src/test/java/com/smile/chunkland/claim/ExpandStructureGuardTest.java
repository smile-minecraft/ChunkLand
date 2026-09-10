package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the land-expansion hot path.
 *
 * <p>Expansion validation and the command handler may only read the immutable
 * registry snapshot, the selection session and the injected revision sources.
 * These tests fail the build if the validator or the handler ever reaches for
 * world/chunk loading, SQL, Economy or Bukkit world state — the exact
 * properties the task acceptance criteria pin down. The saga itself owns the
 * ledger/Economy/publish chain (mirroring the claim saga) and is
 * intentionally outside this guard; only the validator and the handler are
 * scanned.
 */
class ExpandStructureGuardTest {

    /** Tokens that must never appear in the expand validator or handler. */
    private static final List<String> FORBIDDEN = List.of(
            "loadChunk",
            "getChunkAt",
            "getChunk(",
            "getHighestBlock",
            "getBlockData(",
            ".getBlock(",
            "getLocation(",
            "getWorld(",
            "prepareStatement",
            "submitAsync",
            "SqlTransaction",
            "Vault",
            "economy.",
            "ClaimEconomy",
            "OperationLedger",
            "PricingTable",
            "OwnerQuotaService",
            "import org.bukkit.World;",
            "import org.bukkit.Chunk;",
            "import org.bukkit.block");

    private static final List<String> GUARDED = List.of(
            "SnapshotExpandValidator.java",
            "ExpandCommandHandler.java");

    @Test
    void expandValidatorAndHandlerNeverTouchWorldChunkSqlOrEconomyApis() throws IOException {
        Path claimDir = locateDir(
                "src/main/java/com/smile/chunkland/claim",
                "chunkland-plugin/src/main/java/com/smile/chunkland/claim");
        Path commandDir = locateDir(
                "src/main/java/com/smile/chunkland/command",
                "chunkland-plugin/src/main/java/com/smile/chunkland/command");
        List<String> violations = new ArrayList<>();
        for (String name : GUARDED) {
            Path file = name.startsWith("Snapshot") ? claimDir.resolve(name) : commandDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                fail("expected expand production source at " + file);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN) {
                if (content.contains(token)) {
                    violations.add(name + " contains forbidden '" + token + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), "expand structure violations: " + violations);
    }

    @Test
    void expandValidatorDocumentsTheNoChunkLoadRule() throws IOException {
        Path claimDir = locateDir(
                "src/main/java/com/smile/chunkland/claim",
                "chunkland-plugin/src/main/java/com/smile/chunkland/claim");
        String content = Files.readString(
                claimDir.resolve("SnapshotExpandValidator.java"), StandardCharsets.UTF_8);
        assertTrue(content.contains("never loads")
                || content.contains("never load")
                || content.contains("no chunk load")
                || content.contains("chunk loading"),
                "SnapshotExpandValidator must document the no-chunk-load rule");
    }

    private static Path locateDir(String... candidates) {
        for (String candidate : candidates) {
            Path dir = Path.of(candidate);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        fail("cannot locate production sources from working dir " + Path.of("").toAbsolutePath());
        throw new AssertionError("unreachable");
    }
}
