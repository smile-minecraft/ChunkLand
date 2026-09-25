package com.smile.chunkland.command;

import java.util.List;
import java.util.Objects;

/**
 * Language-key entry point for the {@code /land help} screens.
 *
 * <p>The live help reply keeps its current layout and still answers from
 * {@code command.land.help}; the help-UX follow-up composes its prompt,
 * per-subcommand entries and unknown-subcommand hint from the keys here
 * through the regular {@link ReplySink} path. Keys live under
 * {@code command.land.help_detail} because the existing
 * {@code command.land.help} entry is a scalar block that YAML cannot nest
 * anything below.</p>
 */
public final class LandHelpKeys {

    /** Key prefix for every help-detail entry. */
    public static final String PREFIX = "command.land.help_detail.";

    /** Prompt line above the help entries. */
    public static final String PROMPT = PREFIX + "prompt";

    /** Hint for {@code /land help <unknown>}. */
    public static final String UNKNOWN_HINT = PREFIX + "unknown_hint";

    /** Permission-node line between a subcommand detail and its usage. */
    public static final String PERMISSION_LINE = PREFIX + "permission";

    private LandHelpKeys() {
    }

    /** Short one-line description key for one subcommand. */
    public static String shortKey(String subcommand) {
        return PREFIX + checked(subcommand) + ".short";
    }

    /** Full description key for one subcommand. */
    public static String detailKey(String subcommand) {
        return PREFIX + checked(subcommand) + ".detail";
    }

    /** Usage key for one subcommand. */
    public static String usageKey(String subcommand) {
        return PREFIX + checked(subcommand) + ".usage";
    }

    /** All three keys for one subcommand, in short/detail/usage order. */
    public static List<String> keysFor(String subcommand) {
        String sub = checked(subcommand);
        return List.of(PREFIX + sub + ".short", PREFIX + sub + ".detail",
                PREFIX + sub + ".usage");
    }

    private static String checked(String subcommand) {
        Objects.requireNonNull(subcommand, "subcommand");
        if (!LandCommand.SUBCOMMANDS.contains(subcommand)) {
            throw new IllegalArgumentException("unknown subcommand: " + subcommand);
        }
        return subcommand;
    }
}
