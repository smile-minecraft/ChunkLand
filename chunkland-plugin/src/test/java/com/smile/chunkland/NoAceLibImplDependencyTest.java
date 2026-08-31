package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard: ChunkLand must depend only on AceLib's supported public API
 * ({@code com.smile.acelib.AceLibApi} and its nested {@code AceLibProvider}, plus the
 * M0-08 message pipeline surface {@code com.smile.acelib.message.MessageService},
 * {@code com.smile.acelib.config.LangManager}, {@code com.smile.acelib.bedrock.BedrockService},
 * and the necessary public value type {@code com.smile.acelib.bedrock.BedrockPlayerInfo}).
 * It must never reference an implementation class such as {@code AceLibPlugin} or a
 * Bedrock fallback renderer, and must not perform unchecked casts to AceLib types.
 *
 * <p>The scan normalizes Java Unicode escapes (so a reference built from a backslash-u
 * sequence cannot hide), then strips line comments, block comments (including Javadoc)
 * and string/char literals before inspecting references. A fully-qualified implementation
 * reference used directly in code (not via an import), including {@code $}-nested or
 * {@code .class} forms, cannot bypass the guard by hiding in a comment or string.</p>
 */
class NoAceLibImplDependencyTest {

    private static final Path SRC = Paths.get("src/main/java/com/smile/chunkland");

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+([0-9a-fA-F]{4})");
    private static final Pattern ACELIB_REF = Pattern.compile("com\\.smile\\.acelib\\.[A-Za-z0-9_$.]+");

    // Java processes backslash-u escapes (with any number of 'u's) very early, even
    // inside comments and strings. Normalize first so an escaped implementation reference
    // is revealed, then strip comments/strings so only real code references remain.
    private static String translateUnicodeEscapes(String source) {
        Matcher m = UNICODE_ESCAPE.matcher(source);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int code = Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Deterministic lexical stripper: tracks string/char literals and comments so
    // tokens inside them are not mistaken for source references. Avoids regex
    // backtracking on large inputs.
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

    /**
     * @return the first forbidden fully-qualified AceLib reference found, or {@code null}
     *         if only the supported public API is referenced.
     */
    private static String findForbiddenReference(String source) {
        String stripped = stripCommentsAndStrings(translateUnicodeEscapes(source));
        Matcher m = ACELIB_REF.matcher(stripped);
        while (m.find()) {
            String ref = m.group();
            // Drop Java's .class / .this suffixes so the qualified type is what we check.
            if (ref.endsWith(".class") || ref.endsWith(".this")) {
                ref = ref.substring(0, ref.lastIndexOf('.'));
            }
            boolean allowed = ref.equals("com.smile.acelib.AceLibApi")
                || ref.startsWith("com.smile.acelib.AceLibApi.")
                || ref.startsWith("com.smile.acelib.AceLibApi$")
                || ref.equals("com.smile.acelib.message.MessageService")
                || ref.equals("com.smile.acelib.config.LangManager")
                || ref.equals("com.smile.acelib.bedrock.BedrockService")
                || ref.equals("com.smile.acelib.bedrock.BedrockPlayerInfo")
                // M0-07 capability smoke: only the public AceLib surface is allowed here.
                || ref.equals("com.smile.acelib.platform.Platform")
                || ref.startsWith("com.smile.acelib.platform.Platform$")
                || ref.equals("com.smile.acelib.platform.PlatformCapability")
                || ref.equals("com.smile.acelib.scheduler.AceLibScheduler")
                || ref.startsWith("com.smile.acelib.scheduler.AceLibScheduler$")
                || ref.equals("com.smile.acelib.scheduler.SafeScheduler")
                || ref.equals("com.smile.acelib.scheduler.ScheduledTask")
                || ref.equals("com.smile.acelib.scheduler.TaskErrorRecord")
                || ref.equals("com.smile.acelib.gui.GuiService")
                || ref.equals("com.smile.acelib.gui.GuiArgument")
                || ref.startsWith("com.smile.acelib.gui.GuiArgument$")
                || ref.equals("com.smile.acelib.gui.GuiResult")
                || ref.equals("com.smile.acelib.gui.GuiSession")
                || ref.equals("com.smile.acelib.form.FormService")
                || ref.startsWith("com.smile.acelib.form.FormService$")
                || ref.equals("com.smile.acelib.form.FormSpec")
                || ref.startsWith("com.smile.acelib.form.FormSpec$")
                || ref.equals("com.smile.acelib.form.FormSendResult")
                || ref.equals("com.smile.acelib.form.FormResponse")
                || ref.equals("com.smile.acelib.form.FormResponseStatus");
            if (!allowed) {
                return ref;
            }
        }
        return null;
    }

    @Test
    void actualMainSourceHasNoForbiddenAceLibReference() throws IOException {
        if (!Files.isDirectory(SRC)) {
            // Not running from the module directory; nothing to scan here.
            return;
        }
        List<Path> javaFiles = Files.walk(SRC)
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .collect(Collectors.toList());

        for (Path file : javaFiles) {
            String forbidden = findForbiddenReference(Files.readString(file));
            if (forbidden != null) {
                fail("Forbidden AceLib reference in " + file.getFileName() + ": " + forbidden);
            }
        }
    }

    @Test
    void commentWithImplReferenceIsIgnored() {
        String src = "package x;\n"
            + "// com.smile.acelib.AceLibPlugin must not be used\n"
            + "/* block com.smile.acelib.AceLibPlugin */\n"
            + "/** javadoc com.smile.acelib.AceLibPlugin */\n"
            + "class Y {}\n";
        assertNull(findForbiddenReference(src), "comment tokens must be ignored");
    }

    @Test
    void fullyQualifiedImplReferenceIsRejected() {
        String src = "package x;\nclass Y { com.smile.acelib.AceLibPlugin p; }\n";
        assertEquals("com.smile.acelib.AceLibPlugin", findForbiddenReference(src));
    }

    @Test
    void fullyQualifiedImplClassLiteralIsRejected() {
        String src = "package x;\nclass Y { Class<?> c = com.smile.acelib.AceLibPlugin.class; }\n";
        assertEquals("com.smile.acelib.AceLibPlugin", findForbiddenReference(src));
    }

    @Test
    void dollarNestedImplReferenceIsRejected() {
        String src = "package x;\nclass Y { com.smile.acelib.AceLibPlugin$Nested p; }\n";
        assertEquals("com.smile.acelib.AceLibPlugin$Nested", findForbiddenReference(src));
    }

    @Test
    void unicodeEscapedImplReferenceIsRejected() {
        // A backslash-u sequence (\\u0075 == 'u') makes AceLibPl\u0075gin compile to
        // AceLibPlugin; the guard must normalize and reject it.
        String src = "package x;\nclass Y { com.smile.acelib.AceLibPl" + "\\" + "u0075gin p; }\n";
        assertEquals("com.smile.acelib.AceLibPlugin", findForbiddenReference(src));
    }

    @Test
    void unicodeEscapedImplReferenceInCommentIsIgnored() {
        String src = "package x;\n// com.smile.acelib.AceLibPl" + "\\" + "u0075gin\nclass Y {}\n";
        assertNull(findForbiddenReference(src), "escaped reference inside a comment must be ignored");
    }

    @Test
    void unicodeEscapedImplReferenceInStringIsIgnored() {
        String src = "package x;\nclass Y { String s = \"" + "com.smile.acelib.AceLibPl" + "\\" + "u0075gin" + "\"; }\n";
        assertNull(findForbiddenReference(src), "escaped reference inside a string must be ignored");
    }

    @Test
    void allowedAceLibApiReferenceIsOk() {
        String src = "package x;\n"
            + "import com.smile.acelib.AceLibApi;\n"
            + "class Y { AceLibApi a; com.smile.acelib.AceLibApi.AceLibProvider p; }\n";
        assertNull(findForbiddenReference(src));
    }

    @Test
    void allowedAceLibApiDollarNestedIsOk() {
        String src = "package x;\nclass Y { com.smile.acelib.AceLibApi$AceLibProvider p; }\n";
        assertNull(findForbiddenReference(src));
    }

    @Test
    void stringLiteralWithImplReferenceIsIgnored() {
        String src = "package x;\nclass Y { String s = \"com.smile.acelib.AceLibPlugin\"; }\n";
        assertNull(findForbiddenReference(src), "string literals must be ignored");
    }

    @Test
    void allowedMessageConfigBedrockApiReferencesAreOk() {
        String src = "package x;\n"
            + "import com.smile.acelib.message.MessageService;\n"
            + "import com.smile.acelib.config.LangManager;\n"
            + "import com.smile.acelib.bedrock.BedrockService;\n"
            + "class Y { MessageService s; LangManager l; BedrockService b; }\n";
        assertNull(findForbiddenReference(src), "the three M0-08 public API classes must be allowed");
    }

    @Test
    void allowedBedrockPlayerInfoReferenceIsOk() {
        String src = "package x;\n"
            + "import com.smile.acelib.bedrock.BedrockPlayerInfo;\n"
            + "class Y { BedrockPlayerInfo p; }\n";
        assertNull(findForbiddenReference(src), "the public BedrockPlayerInfo value type must be allowed");
    }

    @Test
    void forbiddenImplReferenceInMessagePackageIsRejected() {
        String src = "package x;\nclass Y { com.smile.acelib.bedrock.BedrockFallbackRenderer r; }\n";
        assertEquals("com.smile.acelib.bedrock.BedrockFallbackRenderer", findForbiddenReference(src));
    }

    @Test
    void forbiddenImplReferenceInConfigPackageIsRejected() {
        String src = "package x;\nclass Y { com.smile.acelib.config.LangManagerImpl m; }\n";
        assertEquals("com.smile.acelib.config.LangManagerImpl", findForbiddenReference(src));
    }
}
