package com.smile.chunkland.gui;

import java.util.List;
import java.util.Objects;

/**
 * Caller-injected visible copy for the management GUI.
 *
 * <p>The pages never look up a language service and never embed user-visible
 * prose: every title, line, item name and lore slot is either a resolved
 * string or a function the caller built from its own language templates.
 * Dynamic slots stay functions (rather than pre-rendered strings) because
 * counts, enum names and conflict flags are only known while a page is
 * being composed. All values are plain display text; any MiniMessage in
 * the language templates is already resolved before injection, so the
 * Bukkit adapter renders these strings literally.</p>
 *
 * <p>The second-layer rows end with either {@link #toggleLine()} (the row
 * can be clicked to change its land default) or {@link #readOnlyLine()}
 * (the row is view-only), so the viewer always knows what a click does.
 * The confirm page composes from {@link #defaultLore()} and
 * {@link #confirmTexts()}.</p>
 */
public record ManagementGuiTexts(
        String rootTitle,
        String entryName,
        List<String> entryLines,
        String unavailableTitle,
        List<String> unavailableLines,
        String backName,
        DetailsTitle detailsTitle,
        RowHead rowHead,
        RowRemedies rowRemedies,
        DefaultLore defaultLore,
        ConfirmTexts confirmTexts,
        String readOnlyLine,
        String toggleLine) {

    public ManagementGuiTexts {
        Objects.requireNonNull(rootTitle);
        Objects.requireNonNull(entryName);
        Objects.requireNonNull(entryLines);
        Objects.requireNonNull(unavailableTitle);
        Objects.requireNonNull(unavailableLines);
        Objects.requireNonNull(backName);
        Objects.requireNonNull(detailsTitle);
        Objects.requireNonNull(rowHead);
        Objects.requireNonNull(rowRemedies);
        Objects.requireNonNull(defaultLore);
        Objects.requireNonNull(confirmTexts);
        Objects.requireNonNull(readOnlyLine);
        Objects.requireNonNull(toggleLine);
        entryLines = List.copyOf(entryLines);
        unavailableLines = List.copyOf(unavailableLines);
    }

    /** Second-layer title from the final-effect counts. */
    @FunctionalInterface
    public interface DetailsTitle {
        String title(int denyCount, int allowCount);
    }

    /** One permission row head from display names plus the conflict flag. */
    @FunctionalInterface
    public interface RowHead {
        String head(String action, String outcome, String layer, boolean conflict);
    }

    /** Conflict or plain remedy lore for one row; exactly three lines. */
    @FunctionalInterface
    public interface RowRemedies {
        List<String> lore(String layer, String outcome, boolean conflict);
    }

    /**
     * Land-default lore line for one action. A {@code null} state renders
     * the unset wording (inherits global); the confirm-toggle follow-up
     * supplies the live default.
     */
    @FunctionalInterface
    public interface DefaultLore {
        String line(String action, String state);
    }

    /** Confirm-page copy: title from the action, the target-state line,
     *  the three buttons, and the fail-closed failure line. */
    public record ConfirmTexts(ConfirmTitle title, String confirmName,
            String cancelName, String backName, TargetLine targetLine,
            String failedLine) {
        public ConfirmTexts {
            Objects.requireNonNull(title);
            Objects.requireNonNull(confirmName);
            Objects.requireNonNull(cancelName);
            Objects.requireNonNull(backName);
            Objects.requireNonNull(targetLine);
            Objects.requireNonNull(failedLine);
        }

        /** Confirm-page title from the toggled action display name. */
        @FunctionalInterface
        public interface ConfirmTitle {
            String title(String action);
        }

        /**
         * Target-state line for the pending transition. A {@code null}
         * state renders the unset wording, mirroring
         * {@link DefaultLore#line}.
         */
        @FunctionalInterface
        public interface TargetLine {
            String line(String state);
        }
    }
}
