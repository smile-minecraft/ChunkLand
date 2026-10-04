package com.smile.chunkland.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps private planning material out of everything that ships.
 *
 * <p>The public repository exposes the two modules, the shipped config, the
 * helper scripts and the CI workflow. Prose in those files has to stand on its
 * own: a reader outside the project cannot open the private planning docs, the
 * decision log or the task breakdown, so naming them there leaves a dead
 * reference instead of explaining the code.</p>
 *
 * <p>Comments are inspected separately from shipped text. In both passes only
 * prose is read: the string literals a player sees, a diagnostic a server
 * operator reads in a log, and the plugin descriptor are all outside the
 * repository, so a plan reference there cannot be resolved by anyone. A stored
 * value that happens to look like a plan reference is exempt, and every exemption
 * is declared next to the rules it applies to.</p>
 */
class InternalReferenceLeakTest {

    /** One class of forbidden reference, with the label used in failure output. */
    private record Rule(String label, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("specification section marker", Pattern.compile("§")),
            new Rule("reference to the specification", Pattern.compile("(?i)\\bspecs?\\b")),
            new Rule("private planning document", Pattern.compile(
                    "(?i)task-breakdown|implementation-plan|docs/decisions/|docs/verification/|企劃書?")),
            new Rule("private decision log directory", Pattern.compile("\\.project-doc")),
            new Rule("decision record id", Pattern.compile("\\bD\\d{3}\\b")),
            // A milestone name is the leakiest form: a comment leaning on one reads
            // as broken prose once the plan behind it is gone. The Apple-chip
            // lookbehind keeps the one legitimate bare chip name in this repo.
            new Rule("milestone or task id", Pattern.compile(
                    "(?<!Apple )\\b(?:CL-)?M[0-9](?:-[0-9]+)*\\b|\\bV[0-9]-[0-9]+\\b")));

    /**
     * Literal values that name a Java parameter or a third-party API term rather
     * than a private document. AceLib's form builder takes a form object of that
     * name, so null-check messages legitimately echo it.
     */
    private static final Set<String> ALLOWED_LITERALS = Set.of("spec");

    /**
     * Main-source files whose literals are durable record data, not prose. The
     * audit action registry stores the writer task id on every audit row, so
     * those ids are part of the persisted record read by operators and
     * downstream tooling; rewriting them would orphan stored rows.
     */
    private static final Set<String> DATA_ONLY_SOURCES = Set.of(
            "chunkland-plugin/src/main/java/com/smile/chunkland/persistence/AuditActions.java");

    private static Path findProjectRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(cur.resolve("settings.gradle.kts"))
                    && Files.isDirectory(cur.resolve("chunkland-api"))) {
                return cur;
            }
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        return Paths.get("").toAbsolutePath();
    }

    /** Labels of every rule {@code comments} trips, in declaration order. */
    private static List<String> labels(String comments) {
        List<String> found = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(comments).find()) {
                found.add(rule.label());
            }
        }
        return found;
    }

    private static List<Path> filesUnder(Path root, String relativeDir, String suffix) {
        Path dir = root.resolve(relativeDir);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    private static List<String> scanJavaComments(Path root, String relativeDir) {
        Path base = root.resolve(relativeDir);
        if (!Files.isDirectory(base)) return List.of();
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String origin = root.relativize(file).toString();
                for (String label : labels(SourceComments.javaComments(read(file)))) {
                    found.add(origin + ": " + label);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + base, e);
        }
        return found;
    }

    private static List<String> scanHashComments(Path root, Path file) {
        List<String> found = new ArrayList<>();
        if (!Files.exists(file)) return found;
        String origin = root.relativize(file).toString();
        for (String comment : SourceComments.hashCommentLines(read(file))) {
            for (String label : labels(comment)) {
                found.add(origin + ": " + label);
            }
        }
        return found;
    }

    /**
     * Whether one literal is exempt from the scan. The exemptions are narrow by
     * construction: a whole literal value, or one closed file whose literals are
     * persisted record data. A milestone id in any other file is still reported.
     */
    private static boolean isExempt(String origin, String literal) {
        return ALLOWED_LITERALS.contains(literal) || DATA_ONLY_SOURCES.contains(origin);
    }

    private static void assertClean(String comments, String because) {
        assertEquals(List.of(), labels(comments), because);
    }

    @Test
    void moduleSourceCommentsHaveNoInternalReferences() {
        Path root = findProjectRoot();
        List<String> found = new ArrayList<>();
        for (String module : List.of("chunkland-api", "chunkland-plugin")) {
            for (String set : List.of("src/main/java", "src/test/java")) {
                found.addAll(scanJavaComments(root, module + "/" + set));
            }
        }
        assertTrue(found.isEmpty(), "comment references private planning material: " + found);
    }

    @Test
    void shippedConfigCommentsHaveNoInternalReferences() {
        Path root = findProjectRoot();
        List<String> found = scanHashComments(
                root, root.resolve("chunkland-plugin/src/main/resources/config.yml"));
        assertTrue(found.isEmpty(), "shipped config references private planning material: " + found);
    }

    @Test
    void helperScriptCommentsHaveNoInternalReferences() {
        Path root = findProjectRoot();
        List<String> found = new ArrayList<>();
        for (Path file : filesUnder(root, "scripts", ".sh")) {
            found.addAll(scanHashComments(root, file));
        }
        assertTrue(found.isEmpty(), "helper scripts reference private planning material: " + found);
    }

    @Test
    void workflowCommentsHaveNoInternalReferences() {
        Path root = findProjectRoot();
        List<String> found = new ArrayList<>();
        for (Path file : filesUnder(root, ".github/workflows", ".yml")) {
            found.addAll(scanHashComments(root, file));
        }
        assertTrue(found.isEmpty(), "CI workflow references private planning material: " + found);
    }

    @Test
    void shippedStringsCarryNoInternalReferences() {
        Path root = findProjectRoot();
        List<String> found = new ArrayList<>();
        for (String module : List.of("chunkland-api", "chunkland-plugin")) {
            Path base = root.resolve(module + "/src/main/java");
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String origin = root.relativize(file).toString();
                    for (String literal : SourceComments.javaStringLiterals(read(file))) {
                        if (isExempt(origin, literal)) continue;
                        for (String label : labels(literal)) {
                            found.add(origin + ": " + label + " in \"" + literal + "\"");
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot walk " + base, e);
            }
        }
        assertTrue(found.isEmpty(),
                "user-visible string references private planning material: " + found);
    }

    @Test
    void shippedPluginDescriptorCarriesNoInternalReferences() {
        Path root = findProjectRoot();
        Path file = root.resolve("chunkland-plugin/src/main/resources/plugin.yml");
        List<String> found = new ArrayList<>();
        if (Files.exists(file)) {
            String origin = root.relativize(file).toString();
            for (String value : SourceComments.yamlScalarValues(read(file))) {
                for (String label : labels(value)) {
                    found.add(origin + ": " + label + " in \"" + value + "\"");
                }
            }
        }
        assertTrue(found.isEmpty(), "plugin descriptor references private planning material: " + found);
    }

    @Test
    void scannerReadsStringLiteralsOnly() {
        assertEquals(List.of(),
                labels(SourceComments.javaStringLiterals("String k = \"message key\";").get(0)),
                "an ordinary literal must be readable and clean");
        assertEquals(List.of("decision record id"),
                labels(SourceComments.javaStringLiterals("// see D001\nString k = \"D001\";").get(0)),
                "a comment must not be read as program data, so only the literal is reported");
        assertEquals(List.of("milestone or task id"),
                labels(SourceComments.javaStringLiterals("String t = \"M0-07 smoke\";").get(0)),
                "a milestone in shipped text must be reported");
        assertEquals(List.of("milestone or task id"),
                labels(SourceComments.javaStringLiterals("String s = \"\"\"\nCL-M2-21\n\"\"\";").get(0)),
                "a text block must be read as program data");
    }

    /**
     * A char literal is program data too, so the literal scan must step over it
     * rather than read its contents as string syntax. A double-quote char such
     * as the {@code append('"')} used to hand-build JSON carries one quote, which
     * used to start a string that ran to the next quote in the file: the
     * boundaries after it inverted, so a real internal reference on the next
     * line was absorbed into the swallowed text and went unreported.
     */
    @Test
    void scannerSkipsCharLiteralsWhenReadingStrings() {
        assertEquals(List.of("D001"),
                SourceComments.javaStringLiterals("char q = '\"'; String s = \"D001\";"),
                "a double-quote char literal must not swallow the string after it");
        String aroundChain = "out.append(\"name\");\n"
                + "out.append('\"').append(escape(name)).append('\"');\n"
                + "out.append(\"D001\");\n";
        assertEquals(List.of("name", "D001"),
                SourceComments.javaStringLiterals(aroundChain),
                "the strings on both sides of a quote char literal must both be read");
        assertEquals(List.of("decision record id"),
                labels(SourceComments.javaStringLiterals(aroundChain).get(1)),
                "a string after a quote char literal must still be scanned as program data");
        assertEquals(List.of("name", "null", "cuboid"),
                SourceComments.javaStringLiterals(
                        "out.append(\"name\");\n"
                                + "out.append(\"null\");\n"
                                + "out.append('\"').append(escape(name)).append('\"');\n"
                                + "out.append(\"cuboid\");\n"),
                "every literal around a quote char literal must be reported exactly");
        assertEquals(List.of("D001"),
                SourceComments.javaStringLiterals("char q = '\\\"'; String s = \"D001\";"),
                "an escaped quote char literal must not swallow the string after it");
        assertEquals(List.of("D001"),
                SourceComments.javaStringLiterals(
                        "char a = '\\''; char b = '\\\\'; char c = '/'; String s = \"D001\";"),
                "an escaped single quote, an escaped backslash and a slash char must not open a string");
        assertEquals(List.of("\nD001\n"),
                SourceComments.javaStringLiterals("char q = '\"';\nString s = \"\"\"\nD001\n\"\"\";\n"),
                "a text block after a char literal must be read as one literal");
    }

    @Test
    void exemptionsAreNarrow() {
        String gui = "chunkland-plugin/src/main/java/com/smile/chunkland/gui/BedrockManageForms.java";
        String audit =
                "chunkland-plugin/src/main/java/com/smile/chunkland/persistence/AuditActions.java";
        String probe =
                "chunkland-plugin/src/main/java/com/smile/chunkland/capability/M0CapabilityProbe.java";
        assertTrue(isExempt(gui, "spec"), "the AceLib form spec parameter name stays allowed");
        assertTrue(isExempt(audit, "CL-M2-09"),
                "an audit writer id stays allowed in the registry that persists it");
        assertFalse(isExempt(gui, "Server Land is not transferable (spec section twelve)"),
                "an exemption must not cover a whole sentence that merely starts the same way");
        assertFalse(isExempt(probe, "CL-M2-09"),
                "an audit writer id is not allowed outside the registry that persists it");
    }

    @Test
    void scannerReadsJavaCommentsAndSkipsProgramData() {
        assertEquals(List.of("specification section marker", "reference to the specification"),
                labels(SourceComments.javaComments("// see spec §4\n")),
                "a line comment naming the specification must be reported");
        assertEquals(List.of("decision record id"),
                labels(SourceComments.javaComments("/**\n * See D001 for context.\n */\n")),
                "a javadoc naming a decision record must be reported");
        assertEquals(List.of("milestone or task id"),
                labels(SourceComments.javaComments("int x = 1; // CL-M2-21\n")),
                "a line comment naming a task id must be reported");
        assertEquals(List.of("milestone or task id"),
                labels(SourceComments.javaComments("// the M5 gate\n")),
                "a bare milestone name in a comment must be reported");
        assertClean(SourceComments.javaComments("com.smile.acelib.form.FormSpec spec = null;"),
                "a third-party form spec type name is program data, not a planning reference");
        assertClean(SourceComments.javaComments("// measured on an Apple M4\n"),
                "a chip name is not a milestone name");
    }

    @Test
    void scannerLeavesProgramDataAlone() {
        assertClean(SourceComments.javaComments("String s = \"CL-M2-21\";"),
                "an audit task id stored as program data is not a comment");
        assertClean(SourceComments.javaComments("String s = \"/* spec §4 */\";"),
                "a comment marker inside a literal does not open a comment");
        assertClean(SourceComments.javaComments("String s = \"\"\"\nD001\n\"\"\";"),
                "a text block is program data, not a comment");
        assertClean(SourceComments.javaComments("char c = '/'; String s = \"ok\";"),
                "a slash inside a char literal must not open a comment");
    }

    @Test
    void scannerReadsWholeCommentLinesOfConfigAndScripts() {
        assertEquals(List.of("specification section marker", "private planning document"),
                labels(SourceComments.hashCommentLines("# per 企劃 §28").get(0)),
                "a config comment naming the plan must be reported");
        assertTrue(SourceComments.hashCommentLines("key: value").isEmpty(),
                "a config value line is data, not a comment");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}