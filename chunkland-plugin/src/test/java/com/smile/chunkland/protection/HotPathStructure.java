package com.smile.chunkland.protection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Shared source scanner for protection hot-path structure tests.
 *
 * <p>One token list, one matching rule, reused by every hot-path scope
 * (protection, rejection messaging, land rules) so a second ad-hoc scanner
 * can never drift out of sync. Two profiles exist because the layers have
 * different duties:
 *
 * <ul>
 *   <li>Hot-path profile: no persistence, no economy, no world loading, no
 *       blocking. Applies to every hot-path file.</li>
 *   <li>Render profile: no Adventure/MiniMessage rendering on the event
 *       thread. Applies only to the protection package itself, because the
 *       rejection renderer builds its {@code Component} lazily and only for
 *       a real send.</li>
 * </ul>
 *
 * <p>Matching is line-based and case-insensitive. Pure comment lines (line
 * comments and block-comment continuations) are skipped: a doc sentence
 * such as "no SQL/Economy/chunk load" documents the prohibition without
 * referencing the API, and only executable code can take a dependency.
 * Planted fakes in the self-proof test use import statements and call
 * sites, which are never comments.
 */
final class HotPathStructure {

    private static final List<String> HOT_PATH_TOKENS = List.of(
            "java.sql",
            "jdbc",
            "sqlite",
            "economy",
            "vault",
            "loadchunk",
            "getchunk",
            "thread.sleep",
            "synchronized",
            ".wait(",
            "future.get");

    private static final List<String> RENDER_TOKENS = List.of(
            "minimessage",
            "net.kyori");

    private HotPathStructure() {
    }

    /**
     * @return the ban reason when the line touches persistence, economy,
     *         world loading, or blocking; empty when the line is clean or a
     *         pure comment.
     */
    static Optional<String> hotPathViolation(String line) {
        String code = codePart(line);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        for (String token : HOT_PATH_TOKENS) {
            String haystack = code;
            if (token.equals("vault")) {
                // Material.VAULT is a Bukkit trial-chamber block kind, not the
                // Vault economy API: strip the qualified constant so the
                // economy ban keeps firing on vaultHook, Vault.getEconomy,
                // and net.milkbowl imports without flagging the block.
                haystack = code.replace("material.vault", "");
            }
            if (haystack.contains(token)) {
                return Optional.of("hot path must not reference '" + token + "': " + line.trim());
            }
        }
        return Optional.empty();
    }

    /**
     * @return the ban reason when the line renders chat output on the event
     *         thread; empty when the line is clean or a pure comment.
     */
    static Optional<String> renderViolation(String line) {
        String code = codePart(line);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        for (String token : RENDER_TOKENS) {
            if (code.contains(token)) {
                return Optional.of(
                        "hot path must not render messages ('" + token + "'): " + line.trim());
            }
        }
        return Optional.empty();
    }

    /**
     * Scans files with the hot-path profile.
     *
     * @return one {@code "name:line: reason"} entry per violation, in file
     *         order; empty when every file is clean.
     */
    static List<String> scanHotPath(List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                int lineNumber = i + 1;
                hotPathViolation(lines.get(i)).ifPresent(reason -> violations.add(
                        file.getFileName() + ":" + lineNumber + ": " + reason));
            }
        }
        return violations;
    }

    /**
     * Scans files with the render profile.
     *
     * @return one {@code "name:line: reason"} entry per violation, in file
     *         order; empty when every file is clean.
     */
    static List<String> scanRender(List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                int lineNumber = i + 1;
                renderViolation(lines.get(i)).ifPresent(reason -> violations.add(
                        file.getFileName() + ":" + lineNumber + ": " + reason));
            }
        }
        return violations;
    }

    /** Collects every {@code .java} source under a package directory, sorted. */
    static List<Path> javaSourcesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String codePart(String line) {
        String trimmed = line.trim().toLowerCase();
        if (trimmed.isEmpty()
                || trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || trimmed.startsWith("*")
                || trimmed.startsWith("*/")) {
            return "";
        }
        int commentAt = trimmed.indexOf("//");
        return commentAt < 0 ? trimmed : trimmed.substring(0, commentAt);
    }
}
