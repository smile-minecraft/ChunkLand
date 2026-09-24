package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Structural guard: main sources must never invoke {@code ClickEvent.value()}
 * directly. Adventure 4 exposes {@code value()} while Adventure 5 moved the
 * text payload behind {@code payload().value()}, so a direct call compiles
 * against paper-api 26.1.2 but throws {@code NoSuchMethodError} on Folia 26.2
 * for every message carrying a click. All click payload reads must go through
 * the reflection-based compat reader in the message package, which supports
 * both shapes and returns null (never throws) when neither shape is present.
 */
class NoDirectClickEventValueTest {

    private static final Path SRC = Paths.get("src/main/java/com/smile/chunkland");

    /** Direct receiver call, e.g. {@code click.value()} with any spacing. */
    private static final Pattern DIRECT_CLICK_VALUE =
            Pattern.compile("(?i)\\bclick\\w*\\s*\\.\\s*value\\s*\\(");

    /** Chained call, e.g. {@code component.clickEvent().value()}. */
    private static final Pattern CHAINED_CLICK_VALUE =
            Pattern.compile("clickEvent\\s*\\(\\s*\\)\\s*\\.\\s*value\\s*\\(");

    /**
     * Direct Action constant reference, e.g. {@code ClickEvent.Action.SUGGEST_COMMAND}.
     * Adventure 5 narrowed the constant field types, so a direct reference compiles
     * against paper-api 26.1.2 but throws {@code NoSuchFieldError} on Folia 26.2.
     */
    private static final Pattern DIRECT_CLICK_ACTION =
            Pattern.compile("ClickEvent\\s*\\.\\s*Action\\s*\\.");

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

    static String findDirectClickValueCall(String source) {
        String stripped = stripCommentsAndStrings(source);
        if (DIRECT_CLICK_VALUE.matcher(stripped).find()) {
            return "click.value()";
        }
        if (CHAINED_CLICK_VALUE.matcher(stripped).find()) {
            return "clickEvent().value()";
        }
        return null;
    }

    static String findDirectClickActionReference(String source) {
        String stripped = stripCommentsAndStrings(source);
        if (DIRECT_CLICK_ACTION.matcher(stripped).find()) {
            return "ClickEvent.Action.";
        }
        return null;
    }

    @Test
    void actualMainSourceHasNoDirectClickEventValueCall() throws IOException {
        if (!Files.isDirectory(SRC)) {
            // Not running from the module directory; nothing to scan here.
            return;
        }
        List<Path> javaFiles = Files.walk(SRC)
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.getFileName().toString().equals("AdventureClickPayload.java"))
            .collect(Collectors.toList());

        for (Path file : javaFiles) {
            String hit = findDirectClickValueCall(Files.readString(file));
            if (hit != null) {
                fail("Direct ClickEvent value() call in " + file.getFileName()
                        + ": " + hit + " (use AdventureClickPayload.read instead)");
            }
        }
    }

    @Test
    void actualMainSourceHasNoDirectClickEventActionReference() throws IOException {
        if (!Files.isDirectory(SRC)) {
            // Not running from the module directory; nothing to scan here.
            return;
        }
        List<Path> javaFiles = Files.walk(SRC)
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.getFileName().toString().equals("AdventureClickPayload.java"))
            .collect(Collectors.toList());

        for (Path file : javaFiles) {
            String hit = findDirectClickActionReference(Files.readString(file));
            if (hit != null) {
                fail("Direct ClickEvent Action reference in " + file.getFileName()
                        + ": " + hit + " (use AdventureClickPayload action checks instead)");
            }
        }
    }

    @Test
    void directActionReferenceIsRejected() {
        assertEquals("ClickEvent.Action.",
                findDirectClickActionReference("class Y { boolean b = click.action() == ClickEvent.Action.SUGGEST_COMMAND; }"));
    }

    @Test
    void clickFactoryCallsAreIgnored() {
        assertNull(findDirectClickActionReference("class Y { Object e = ClickEvent.suggestCommand(\"/x\"); }"),
                "ClickEvent.runCommand/suggestCommand factories stay allowed");
    }

    @Test
    void actionReferenceInCommentAndStringIsIgnored() {
        assertNull(findDirectClickActionReference("// ClickEvent.Action.SUGGEST_COMMAND\nclass Y {}"),
                "comment tokens must be ignored");
        assertNull(findDirectClickActionReference("class Y { String s = \"ClickEvent.Action.SUGGEST_COMMAND\"; }"),
                "string literals must be ignored");
    }

    @Test
    void directReceiverCallIsRejected() {
        assertEquals("click.value()",
                findDirectClickValueCall("class Y { String s = click.value(); }"));
    }

    @Test
    void chainedClickEventValueCallIsRejected() {
        assertEquals("clickEvent().value()",
                findDirectClickValueCall("class Y { String s = component.clickEvent().value(); }"));
    }

    @Test
    void hoverValueCallIsIgnored() {
        assertNull(findDirectClickValueCall("class Y { Object v = hover.value(); }"),
                "HoverEvent.value() still exists on Adventure 5 and must stay allowed");
    }

    @Test
    void unrelatedDomainValueCallIsIgnored() {
        assertNull(findDirectClickValueCall("class Y { String s = landId.value().toString(); }"),
                "domain record value() accessors must not trip the guard");
    }

    @Test
    void reflectiveLookupByNameIsIgnored() {
        assertNull(findDirectClickValueCall("class Y { Object m = type.getMethod(\"value\"); }"),
                "the compat reader itself uses reflective lookup and must stay allowed");
    }

    @Test
    void commentAndStringTokensAreIgnored() {
        assertNull(findDirectClickValueCall("// click.value() must not be used\nclass Y {}"),
                "comment tokens must be ignored");
        assertNull(findDirectClickValueCall("class Y { String s = \"click.value()\"; }"),
                "string literals must be ignored");
    }
}
