package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Bukkit-free page composition ({@link ManagementGuiPages} plus the
 * injected {@link ManagementGuiTexts} carrier) must not embed user-visible
 * English copy: every visible string arrives through the injected texts.
 *
 * <p>Scope is deliberately narrow — the file that builds item names and
 * lore, plus the text carrier itself. Identifiers (page ids, material
 * hints, {@code requireNonNull} parameter names), javadoc and comments are
 * excluded: only string-literal contents are inspected, against an explicit
 * allowlist. Bedrock forms keep their own copy and stay out of scope here.
 */
class ManagementGuiNoHardcodedTextTest {

    private static final List<String> SCOPED_FILES = List.of(
            "ManagementGuiPages.java",
            "ManagementGuiTexts.java");

    /**
     * Exact literals that are identifiers, never user-visible copy:
     * page ids, material hints resolved by the Bukkit adapter, and
     * {@code requireNonNull} parameter names inside click callbacks
     * and page-entry guards.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "chunkland:land-manage-root",
            "chunkland:land-manage-permissions",
            "chunkland:land-manage-unavailable",
            "chunkland:land-manage-confirm",
            "entry",
            "deny",
            "allow",
            "confirm",
            "cancel",
            "back",
            "unavailable",
            "click",
            "action",
            "texts");

    private static final Pattern LETTER_RUN = Pattern.compile("[A-Za-z]{2,}");

    private static Path mainRoot(String fileName) {
        for (String candidate : List.of(
                "chunkland-plugin/src/main/java/com/smile/chunkland/gui/" + fileName,
                "src/main/java/com/smile/chunkland/gui/" + fileName)) {
            Path direct = Paths.get(candidate);
            if (Files.exists(direct)) {
                return direct;
            }
            Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(candidate);
            if (Files.exists(fromHere)) {
                return fromHere;
            }
        }
        throw new IllegalStateException("cannot locate " + fileName);
    }

    /** Comment-aware literal collector: strips comments, keeps "..." contents. */
    static List<String> stringLiterals(String source) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        boolean escaped = false;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (current != null) {
                if (escaped) {
                    current.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    out.add(current.toString());
                    current = null;
                } else {
                    current.append(c);
                }
                i++;
                continue;
            }
            if (c == '"') {
                if (i + 2 < n && source.charAt(i + 1) == '"'
                        && source.charAt(i + 2) == '"') {
                    throw new IllegalStateException(
                            "text blocks are not allowed in scoped GUI composition files");
                }
                current = new StringBuilder();
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < n) {
                    if (source.charAt(i) == '*' && i + 1 < n
                            && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
            } else if (c == '\'') {
                // Skip char literals so a stray quote never opens a string.
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        if (current != null) {
            throw new IllegalStateException("unterminated string literal in scoped file");
        }
        return out;
    }

    @Test
    void scopedCompositionFilesExist() {
        for (String file : SCOPED_FILES) {
            assertTrue(Files.exists(mainRoot(file)), "scoped file must exist: " + file);
        }
    }

    @Test
    void noUserVisibleEnglishLiteralsInPageComposition() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String file : SCOPED_FILES) {
            String source = Files.readString(mainRoot(file));
            for (String literal : stringLiterals(source)) {
                if (ALLOWLIST.contains(literal)) {
                    continue;
                }
                if (LETTER_RUN.matcher(literal).find()) {
                    violations.add(file + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "user-visible copy must come from injected texts, found literals: " + violations);
    }

    @Test
    void literalScannerIgnoresCommentsAndFindsRealCopy() {
        String source = "// \"Back\" in a comment is not copy\n"
                + "/* block \"Cancel\" is not copy */\n"
                + "/** javadoc \"Confirm\" is not copy */\n"
                + "String id = \"chunkland:land-manage-root\";\n"
                + "String copy = \"Back\";\n";
        List<String> literals = stringLiterals(source);
        assertFalse(literals.contains("Cancel"), "comment contents must not scan as literals");
        assertTrue(literals.contains("Back"), "real literals must be collected");
        assertTrue(literals.contains("chunkland:land-manage-root"));
    }
}
