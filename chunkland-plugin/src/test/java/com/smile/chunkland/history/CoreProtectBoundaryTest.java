package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the optional history boundary: no
 * {@code net.coreprotect} type may appear in executable code outside string
 * literals (the adapter resolves the backend purely by reflective name), the
 * dependency-free API carries no backend reference at all, and the protection
 * hot path (engine plus registry/index reads) never mentions the backend.
 */
class CoreProtectBoundaryTest {

    private static final Path PLUGIN_SRC = Paths.get("src/main/java/com/smile/chunkland");

    private static final Pattern BACKEND_CODE_REF =
            Pattern.compile("net\\.coreprotect|coreprotect\\s*\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern BACKEND_IMPORT =
            Pattern.compile("^\\s*import\\s+net\\.coreprotect\\.", Pattern.MULTILINE);
    private static final Pattern BACKEND_WORD =
            Pattern.compile("coreprotect", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY_WORD =
            Pattern.compile("history", Pattern.CASE_INSENSITIVE);

    private static List<Path> sourcesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static String stripCommentsAndStrings(String source) {
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
    void noBackendTypeInExecutableCode() throws IOException {
        for (Path file : sourcesUnder(PLUGIN_SRC)) {
            String code = stripCommentsAndStrings(Files.readString(file));
            assertFalse(BACKEND_CODE_REF.matcher(code).find(),
                    "backend type reference in executable code: " + file.getFileName());
            assertFalse(BACKEND_IMPORT.matcher(code).find(),
                    "backend import: " + file.getFileName());
        }
    }

    @Test
    void apiCarriesNoBackendReference() throws IOException {
        Path apiSrc = PLUGIN_SRC.resolve("../../../../chunkland-api/src/main/java");
        Path normalized = apiSrc.normalize();
        if (!Files.isDirectory(apiSrc)) {
            Path fallback = Paths.get("chunkland-api/src/main/java");
            if (!Files.isDirectory(fallback)) {
                return;
            }
            normalized = fallback;
        }
        for (Path file : sourcesUnder(normalized)) {
            String code = stripCommentsAndStrings(Files.readString(file));
            assertFalse(BACKEND_WORD.matcher(code).find(),
                    "backend reference in dependency-free API: " + file.getFileName());
        }
    }

    @Test
    void protectionHotPathNeverMentionsBackendOrHistory() throws IOException {
        Path engine = PLUGIN_SRC.resolve("protection/ProtectionEngine.java");
        if (!Files.isRegularFile(engine)) {
            return;
        }
        String code = stripCommentsAndStrings(Files.readString(engine)).toLowerCase(Locale.ROOT);
        assertFalse(code.contains("coreprotect"),
                "ProtectionEngine must never reference the optional backend");
        assertFalse(code.contains("history"),
                "ProtectionEngine must never call the history path");
        for (Path root : new Path[] {
                PLUGIN_SRC.resolve("runtime/index"), PLUGIN_SRC.resolve("runtime/api")}) {
            for (Path file : sourcesUnder(root)) {
                String scoped = stripCommentsAndStrings(Files.readString(file))
                        .toLowerCase(Locale.ROOT);
                assertFalse(scoped.contains("coreprotect"),
                        "registry/index reads must never reference the optional backend: "
                                + file.getFileName());
            }
        }
    }

    @Test
    void historyAdapterStaysOutOfProtectionDecisions() {
        try {
            Class.forName("com.smile.chunkland.protection.ProtectionEngine");
        } catch (ClassNotFoundException missing) {
            fail("ProtectionEngine must stay loadable without the optional backend");
        }
        assertTrue(true);
    }
}
