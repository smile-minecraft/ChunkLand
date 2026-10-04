package com.smile.chunkland.docs;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts comment bodies so scanners can inspect prose without ever touching
 * program data.
 *
 * <p>String literals, char literals and text blocks are skipped, so an id or a
 * marker that lives in program data is never mistaken for a comment. Script and
 * YAML files have no string syntax to walk, so only whole comment lines are
 * taken there.</p>
 */
public final class SourceComments {

    private SourceComments() {}

    /**
     * Returns the bodies of {@code //} and block comments in a Java source,
     * concatenated. Markers inside string, char and text-block literals are
     * skipped.
     */
    public static String javaComments(String src) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int end = src.indexOf('\n', i + 2);
                    if (end < 0) end = n;
                    out.append(src, i + 2, end).append('\n');
                    i = end;
                    continue;
                }
                if (next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    if (end < 0) end = n; else end += 2;
                    out.append(src, i + 2, Math.min(end, n)).append('\n');
                    i = end;
                    continue;
                }
            }
            if (c == '"') {
                if (i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                    int end = src.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? n : end + 3;
                } else {
                    i++;
                    while (i < n) {
                        char s = src.charAt(i);
                        if (s == '\\') {
                            i += 2;
                        } else if (s == '"') {
                            i++;
                            break;
                        } else {
                            i++;
                        }
                    }
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char s = src.charAt(i);
                    if (s == '\\') {
                        i += 2;
                    } else if (s == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                continue;
            }
            i++;
        }
        return out.toString();
    }

    /**
     * Returns every string literal in a Java source, including text blocks, with
     * the surrounding quotes stripped. Comments and char literals are skipped, so
     * this sees only program data. A char literal is skipped rather than read as
     * string syntax because one carrying a double quote, such as the
     * {@code append('"')} used to hand-build JSON, would otherwise open a string
     * that runs to the next quote in the file and inverts every boundary after it.
     */
    public static List<String> javaStringLiterals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    int end = src.indexOf('\n', i + 2);
                    i = end < 0 ? n : end;
                    continue;
                }
                if (next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    i = end < 0 ? n : end + 2;
                    continue;
                }
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char s = src.charAt(i);
                    if (s == '\\') {
                        i += 2;
                    } else if (s == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                continue;
            }
            if (c != '"') {
                i++;
                continue;
            }
            if (i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int end = src.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                out.add(src.substring(i + 3, Math.max(i + 3, stop - 3)));
                i = stop;
                continue;
            }
            StringBuilder lit = new StringBuilder();
            int j = i + 1;
            while (j < n) {
                char s = src.charAt(j);
                if (s == '\\' && j + 1 < n) {
                    lit.append(src, j, j + 2);
                    j += 2;
                    continue;
                }
                if (s == '"') {
                    j++;
                    break;
                }
                lit.append(s);
                j++;
            }
            out.add(lit.toString());
            i = j;
        }
        return out;
    }

    /**
     * Returns the value part of every {@code key: value} line in a YAML file.
     * Comment lines are skipped. Used for shipped descriptors, where the value
     * is player-facing text rather than configuration.
     */
    public static List<String> yamlScalarValues(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String value = line.substring(colon + 1).strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * Returns every whole line of a shell script or YAML file whose first
     * non-blank character opens a comment, with the marker stripped. Value
     * lines are skipped.
     */
    public static List<String> hashCommentLines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("#")) {
                out.add(line.substring(1).strip());
            }
        }
        return out;
    }
}