package com.smile.chunkland.gui;

import java.util.List;
import java.util.Objects;

/**
 * Caller-injected visible copy for the rule management pages.
 *
 * <p>Like {@link ManagementGuiTexts}, every title, line and button name is
 * either a resolved string or a function the caller built from its own
 * language templates. The framework side never embeds user-visible prose.
 * Raw rule, state and source tokens travel as the function arguments; the
 * message pipeline swaps the ones it knows ({@code action}, {@code state},
 * {@code outcome}) for their localized labels at render time.
 */
public record ManagementRuleTexts(
        String entryName,
        List<String> entryLines,
        DetailsTitle detailsTitle,
        RowHead rowHead,
        ExplicitLine explicitLine,
        EffectiveLine effectiveLine,
        String changeHintLine,
        ConfirmTitle confirmTitle,
        ConfirmCurrentLine confirmCurrentLine,
        ConfirmTargetLine confirmTargetLine,
        String confirmAllowName,
        String confirmDenyName,
        String confirmInheritName,
        String confirmCancelName,
        String backName,
        String confirmFailedLine,
        String unavailableTitle,
        List<String> unavailableLines) {

    public ManagementRuleTexts {
        Objects.requireNonNull(entryName);
        Objects.requireNonNull(entryLines);
        Objects.requireNonNull(detailsTitle);
        Objects.requireNonNull(rowHead);
        Objects.requireNonNull(explicitLine);
        Objects.requireNonNull(effectiveLine);
        Objects.requireNonNull(changeHintLine);
        Objects.requireNonNull(confirmTitle);
        Objects.requireNonNull(confirmCurrentLine);
        Objects.requireNonNull(confirmTargetLine);
        Objects.requireNonNull(confirmAllowName);
        Objects.requireNonNull(confirmDenyName);
        Objects.requireNonNull(confirmInheritName);
        Objects.requireNonNull(confirmCancelName);
        Objects.requireNonNull(backName);
        Objects.requireNonNull(confirmFailedLine);
        Objects.requireNonNull(unavailableTitle);
        Objects.requireNonNull(unavailableLines);
        entryLines = List.copyOf(entryLines);
        unavailableLines = List.copyOf(unavailableLines);
    }

    /** Rule-list title from the effective counts. */
    @FunctionalInterface
    public interface DetailsTitle {
        String title(int denyCount, int allowCount);
    }

    /** One rule row head from the display triple. */
    @FunctionalInterface
    public interface RowHead {
        String head(String rule, String explicit, String effective, String source);
    }

    /** Land-override lore line for one rule; {@code null} explicit means unset. */
    @FunctionalInterface
    public interface ExplicitLine {
        String line(String rule, String explicit);
    }

    /** Effective-value lore line for one rule. */
    @FunctionalInterface
    public interface EffectiveLine {
        String line(String effective, String source);
    }

    /** Confirm-page title from the rule. */
    @FunctionalInterface
    public interface ConfirmTitle {
        String title(String rule);
    }

    /** Confirm-page current-triple line. */
    @FunctionalInterface
    public interface ConfirmCurrentLine {
        String line(String explicit, String effective, String source);
    }

    /** Confirm-page target-state line. */
    @FunctionalInterface
    public interface ConfirmTargetLine {
        String line(String state);
    }
}
