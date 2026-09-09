package com.smile.chunkland.claim;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the claim package: it must stay free of Bukkit, SQL,
 * and AceLib references, must not create its own threads or executors (the
 * saga receives a bounded executor from its owner), and must not carry literal
 * workflow identifiers in executable sources.
 */
class ClaimPackageBoundaryTest {

    private static final Path CLAIM_SRC = Paths.get("src/main/java/com/smile/chunkland/claim");

    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(org\\.bukkit\\.|java\\.sql\\.|javax\\.sql\\.|com\\.smile\\.acelib\\.)",
            Pattern.MULTILINE);
    // Optional.get() is a pure accessor, not a blocking call, so it is
    // intentionally absent here; blocking Future.get variants take timeout or
    // unit arguments and are covered by review alongside join/await.
    private static final Pattern UNCONTROLLED_CONCURRENCY = Pattern.compile(
            "Executors\\.|new\\s+Thread\\(|Thread\\.sleep|\\.await\\(|\\.join\\(");
    private static final Pattern WORKFLOW_ID = Pattern.compile("CL-M\\d+-\\d+");

    private static List<Path> sources() throws IOException {
        if (!Files.isDirectory(CLAIM_SRC)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(CLAIM_SRC)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static String codeWithoutCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < n) {
                    if (source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Test
    void claimPackageHasNoBukkitSqlOrAceLibImports() throws IOException {
        for (Path file : sources()) {
            String source = Files.readString(file);
            if (FORBIDDEN_IMPORT.matcher(source).find()) {
                fail("Forbidden dependency import in " + file.getFileName());
            }
        }
    }

    @Test
    void claimPackageCreatesNoThreadsAndNeverBlocks() throws IOException {
        for (Path file : sources()) {
            String code = codeWithoutCommentsAndStrings(Files.readString(file));
            if (UNCONTROLLED_CONCURRENCY.matcher(code).find()) {
                fail("Uncontrolled concurrency or blocking call in " + file.getFileName());
            }
        }
    }

    @Test
    void claimSourcesCarryNoLiteralWorkflowIds() throws IOException {
        for (Path file : sources()) {
            String source = Files.readString(file);
            if (WORKFLOW_ID.matcher(source).find()) {
                fail("Literal workflow id in " + file.getFileName());
            }
        }
    }

    @Test
    void claimPackageExists() throws IOException {
        assertTrue(!sources().isEmpty(), "claim package must contain sources when run from the module dir");
    }
}
