package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Structural guard for the land-shrink hot path.
 *
 * <p>Shrink validation and the command handler may only read the immutable
 * registry snapshot, the selection session and the injected revision sources.
 * These tests fail the build if the validator or the handler ever reaches for
 * world/chunk loading, SQL, Economy or Bukkit world state — the exact
 * properties the task acceptance criteria pin down. The saga itself owns the
 * ledger/Economy/publish chain (mirroring the refund saga) and is
 * intentionally outside this guard; only the validator and the handler are
 * scanned. A disabled world must never block shrink, so the validator must
 * not reference the world claim policy at all.
 */
class ShrinkStructureGuardTest {

    /** Tokens that must never appear in the shrink validator or handler. */
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
            "WorldClaimPolicy",
            "claim-enabled",
            "claimEnabled",
            "import org.bukkit.World;",
            "import org.bukkit.Chunk;",
            "import org.bukkit.block");

    private static final List<String> GUARDED = List.of(
            "SnapshotShrinkValidator.java",
            "ShrinkCommandHandler.java");

    @Test
    void shrinkValidatorAndHandlerNeverTouchWorldChunkSqlOrEconomyApis() throws IOException {
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
                fail("expected shrink production source at " + file);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN) {
                if (content.contains(token)) {
                    violations.add(name + " contains forbidden '" + token + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), "shrink structure violations: " + violations);
    }

    @Test
    void shrinkValidatorDocumentsTheNoChunkLoadRule() throws IOException {
        Path claimDir = locateDir(
                "src/main/java/com/smile/chunkland/claim",
                "chunkland-plugin/src/main/java/com/smile/chunkland/claim");
        String content = Files.readString(
                claimDir.resolve("SnapshotShrinkValidator.java"), StandardCharsets.UTF_8);
        assertTrue(content.contains("never loads")
                || content.contains("never load")
                || content.contains("no chunk load")
                || content.contains("chunk loading"),
                "SnapshotShrinkValidator must document the no-chunk-load rule");
    }

    @Test
    void shrinkValidatorHasNoWorldClaimGate() throws IOException {
        Path claimDir = locateDir(
                "src/main/java/com/smile/chunkland/claim",
                "chunkland-plugin/src/main/java/com/smile/chunkland/claim");
        String content = Files.readString(
                claimDir.resolve("SnapshotShrinkValidator.java"), StandardCharsets.UTF_8);
        assertFalse(content.contains("WorldClaimPolicy"),
                "shrink must stay allowed when the world disables claims, so the validator "
                        + "must not reference the world claim policy");
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
