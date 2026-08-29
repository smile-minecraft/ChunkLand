package com.smile.chunkland.api.land;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable land name value object (spec §4, §10).
 *
 * <p>Holds the user-supplied {@code displayName} exactly as entered (never
 * trimmed, truncated, or rewritten) together with a stable, normalized
 * {@code nameKey} used for owner-scoped uniqueness. The key is derived by
 * trimming surrounding whitespace and lower-casing with {@link Locale#ROOT},
 * so it never drifts with the JVM default locale (e.g. Turkish {@code I} maps
 * to {@code i}, not the dotless {@code ı}).
 *
 * <p>Control characters (including newline, tab, and ISO controls) and blank
 * input are rejected; the key is always verifiably equal to
 * {@link #normalize(String)} of the display name, so the two can never diverge.
 *
 * <p>Thread-safe: a pure value object with no mutable state.
 */
public record LandName(String displayName, String nameKey) {

    public LandName {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(nameKey, "nameKey");
        String expected = normalize(displayName);
        if (!nameKey.equals(expected)) {
            throw new IllegalArgumentException(
                    "nameKey must equal normalize(displayName); got '" + nameKey + "' expected '" + expected + "'");
        }
    }

    /** Build a {@code LandName} from raw user input, deriving the key. */
    public static LandName of(String displayName) {
        return new LandName(displayName, normalize(displayName));
    }

    /**
     * Normalize a display name into its stable key.
     *
     * <p>Uses Java Unicode-whitespace semantics ({@link String#strip()} /
     * {@link String#isBlank()}, i.e. {@link Character#isWhitespace}): surrounding
     * whitespace of any Unicode category (including U+2003 EM SPACE, but not NBSP,
     * which is a space separator rather than whitespace) is stripped, blank-only
     * input is rejected, control characters are rejected, and the remainder is
     * lower-cased with {@link Locale#ROOT}. This is the single source of truth
     * for blank/strip rules; {@link LandNameKey} and {@link LandNameUniqueness}
     * validate only through this method so the contract cannot diverge.
     */
    public static String normalize(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (containsControlCharacter(displayName)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        String stripped = displayName.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    private static boolean containsControlCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
