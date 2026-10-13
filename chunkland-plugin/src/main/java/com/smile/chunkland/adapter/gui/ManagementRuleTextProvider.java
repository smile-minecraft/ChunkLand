package com.smile.chunkland.adapter.gui;

import com.smile.chunkland.gui.ManagementRuleTexts;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Caller-side builder for the injected rule-GUI texts.
 *
 * <p>Same contract as {@link ManagementGuiTextProvider}: every slot
 * resolves a {@code gui.manage.rules.*} template for the player's locale
 * through the message pipeline and arrives in {@code gui/} as plain display
 * text. Skeleton: every viewer gets the bundled English fallback until the
 * template resolution lands.
 */
public final class ManagementRuleTextProvider {

    public static final String RULE_ENTRY_NAME_KEY = "gui.manage.rules.entry_name";
    public static final String RULE_ENTRY_HINT_KEY = "gui.manage.rules.entry_hint";
    public static final String RULE_DETAILS_TITLE_KEY = "gui.manage.rules.details.title";
    public static final String RULE_ROW_HEAD_KEY = "gui.manage.rules.row.head";
    public static final String RULE_ROW_EXPLICIT_KEY = "gui.manage.rules.row.explicit";
    public static final String RULE_ROW_EFFECTIVE_KEY = "gui.manage.rules.row.effective";
    public static final String RULE_ROW_CHANGE_HINT_KEY = "gui.manage.rules.row.change_hint";
    public static final String RULE_ROW_UNSET_KEY = "gui.manage.rules.row.unset";
    public static final String RULE_CONFIRM_TITLE_KEY = "gui.manage.rules.confirm.title";
    public static final String RULE_CONFIRM_CURRENT_KEY = "gui.manage.rules.confirm.current";
    public static final String RULE_CONFIRM_TARGET_KEY = "gui.manage.rules.confirm.target";
    public static final String RULE_CONFIRM_ALLOW_NAME_KEY = "gui.manage.rules.confirm.allow_name";
    public static final String RULE_CONFIRM_DENY_NAME_KEY = "gui.manage.rules.confirm.deny_name";
    public static final String RULE_CONFIRM_INHERIT_NAME_KEY =
            "gui.manage.rules.confirm.inherit_name";
    public static final String RULE_CONFIRM_CANCEL_NAME_KEY = "gui.manage.rules.confirm.cancel_name";
    public static final String RULE_CONFIRM_BACK_NAME_KEY = "gui.manage.rules.confirm.back_name";
    public static final String RULE_CONFIRM_FAILED_LINE_KEY = "gui.manage.rules.confirm.failed_line";
    public static final String RULE_UNAVAILABLE_TITLE_KEY = "gui.manage.rules.unavailable.title";
    public static final String RULE_UNAVAILABLE_LINES_KEY = "gui.manage.rules.unavailable.lines";

    private final GuiTemplateRenderer renderer;
    private final Function<UUID, Locale> localeLookup;

    public ManagementRuleTextProvider(GuiTemplateRenderer renderer,
            Function<UUID, Locale> localeLookup) {
        this.renderer = renderer;
        this.localeLookup = localeLookup == null ? uuid -> null : localeLookup;
    }

    /**
     * Production entry: rendering goes through the message pipeline's
     * broadcast path and plain-serializes, because GUI items paint literal
     * text.
     */
    public static ManagementRuleTextProvider withPipeline(ChunkLandMessagePipeline pipeline,
            Function<UUID, Locale> localeLookup) {
        Objects.requireNonNull(pipeline, "pipeline");
        GuiTemplateRenderer renderer = (key, vars, locale) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(
                                pipeline.renderForBroadcast(key, vars, locale));
        return new ManagementRuleTextProvider(renderer, localeLookup);
    }

    /**
     * Builds the injected rule texts for one viewer. Never returns
     * {@code null} and never throws: any failure yields the bundled
     * English fallback.
     */
    public ManagementRuleTexts resolve(UUID playerUuid) {
        Locale locale = localeOf(playerUuid);
        if (renderer == null) {
            return fallbackTexts();
        }
        try {
            return resolveWith(renderer, locale);
        } catch (RuntimeException failed) {
            return fallbackTexts();
        }
    }

    private Locale localeOf(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        try {
            return localeLookup.apply(playerUuid);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ManagementRuleTexts resolveWith(GuiTemplateRenderer renderer, Locale locale) {
        ManagementRuleTexts fallback = fallbackTexts();
        String entryName = renderOr(renderer, RULE_ENTRY_NAME_KEY, Map.of(), locale,
                fallback.entryName());
        java.util.List<String> entryLines = java.util.List.of(renderOr(renderer,
                RULE_ENTRY_HINT_KEY, Map.of(), locale,
                fallback.entryLines().isEmpty() ? "" : fallback.entryLines().get(0)));
        ManagementRuleTexts.DetailsTitle detailsTitle = (deny, allow) -> renderOr(renderer,
                RULE_DETAILS_TITLE_KEY,
                Map.of("deny_count", deny, "allow_count", allow), locale,
                fallback.detailsTitle().title(deny, allow));
        ManagementRuleTexts.RowHead rowHead = (rule, explicit, effective, source) ->
                renderOr(renderer, RULE_ROW_HEAD_KEY,
                        headVars(rule, explicit, effective, source), locale,
                        fallback.rowHead().head(rule, explicit, effective, source));
        ManagementRuleTexts.ExplicitLine explicitLine = (rule, explicit) -> {
            String shown = explicit == null
                    ? renderOr(renderer, RULE_ROW_UNSET_KEY, Map.of(), locale,
                            "Not set (inherits the level above)")
                    : explicit;
            return renderOr(renderer, RULE_ROW_EXPLICIT_KEY,
                    Map.of("action", rule, "state", shown), locale,
                    fallback.explicitLine().line(rule, explicit));
        };
        ManagementRuleTexts.EffectiveLine effectiveLine = (effective, source) ->
                renderOr(renderer, RULE_ROW_EFFECTIVE_KEY,
                        Map.of("outcome", effective, "layer", source), locale,
                        fallback.effectiveLine().line(effective, source));
        String changeHintLine = renderOr(renderer, RULE_ROW_CHANGE_HINT_KEY, Map.of(), locale,
                fallback.changeHintLine());
        ManagementRuleTexts.ConfirmTitle confirmTitle = rule -> renderOr(renderer,
                RULE_CONFIRM_TITLE_KEY, Map.of("action", rule), locale,
                fallback.confirmTitle().title(rule));
        ManagementRuleTexts.ConfirmCurrentLine confirmCurrentLine =
                (explicit, effective, source) -> {
                    String shown = explicit == null
                            ? renderOr(renderer, RULE_ROW_UNSET_KEY, Map.of(), locale,
                                    "Not set (inherits the level above)")
                            : explicit;
                    return renderOr(renderer, RULE_CONFIRM_CURRENT_KEY,
                            Map.of("state", shown, "outcome", effective, "layer", source),
                            locale,
                            fallback.confirmCurrentLine().line(explicit, effective, source));
                };
        ManagementRuleTexts.ConfirmTargetLine confirmTargetLine = state ->
                renderOr(renderer, RULE_CONFIRM_TARGET_KEY,
                        Map.of("state", state == null ? "" : state), locale,
                        fallback.confirmTargetLine().line(state));
        String confirmAllowName = renderOr(renderer, RULE_CONFIRM_ALLOW_NAME_KEY, Map.of(),
                locale, fallback.confirmAllowName());
        String confirmDenyName = renderOr(renderer, RULE_CONFIRM_DENY_NAME_KEY, Map.of(),
                locale, fallback.confirmDenyName());
        String confirmInheritName = renderOr(renderer, RULE_CONFIRM_INHERIT_NAME_KEY, Map.of(),
                locale, fallback.confirmInheritName());
        String confirmCancelName = renderOr(renderer, RULE_CONFIRM_CANCEL_NAME_KEY, Map.of(),
                locale, fallback.confirmCancelName());
        String backName = renderOr(renderer, RULE_CONFIRM_BACK_NAME_KEY, Map.of(),
                locale, fallback.backName());
        String confirmFailedLine = renderOr(renderer, RULE_CONFIRM_FAILED_LINE_KEY, Map.of(),
                locale, fallback.confirmFailedLine());
        String unavailableTitle = renderOr(renderer, RULE_UNAVAILABLE_TITLE_KEY, Map.of(),
                locale, fallback.unavailableTitle());
        java.util.List<String> unavailableLines = splitLines(renderOr(renderer,
                RULE_UNAVAILABLE_LINES_KEY, Map.of(), locale,
                String.join("\n", fallback.unavailableLines())));
        return new ManagementRuleTexts(entryName, entryLines, detailsTitle, rowHead,
                explicitLine, effectiveLine, changeHintLine, confirmTitle, confirmCurrentLine,
                confirmTargetLine, confirmAllowName, confirmDenyName, confirmInheritName,
                confirmCancelName, backName, confirmFailedLine, unavailableTitle,
                unavailableLines);
    }

    private static String renderOr(GuiTemplateRenderer renderer, String key,
            Map<String, Object> vars, Locale locale, String fallback) {
        try {
            String rendered = renderer.renderPlain(key, vars, locale);
            if (rendered == null || rendered.isBlank()) {
                return fallback;
            }
            return rendered;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static java.util.List<String> splitLines(String block) {
        if (block == null || block.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : block.split("\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return java.util.List.copyOf(out);
    }

    /**
     * Bundled English defaults: the fail-closed wording used when no
     * pipeline is available or a template fails.
     */
    public static ManagementRuleTexts fallbackTexts() {
        return new ManagementRuleTexts(
                "Environment rules",
                java.util.List.of("View and change what the world itself may do on this land."),
                (deny, allow) -> "Land Rules (" + deny + " denied, " + allow + " allowed)",
                (rule, explicit, effective, source) -> rule + ": " + explicit + " -> "
                        + effective + " (" + source + ")",
                (rule, explicit) -> "Land override for " + rule + ": "
                        + (explicit == null ? "Not set (inherits the level above)" : explicit),
                (effective, source) -> "Effective now: " + effective + " (from " + source + ")",
                "Click to change this rule.",
                rule -> "Confirm rule change: " + rule,
                (explicit, effective, source) -> "Now explicitly "
                        + (explicit == null ? "unset" : explicit) + ", effectively " + effective
                        + " (from " + source + ")",
                state -> "After confirming it becomes: " + state,
                "Allow",
                "Deny",
                "Inherit",
                "Cancel",
                "Back",
                "The change did not go through and nothing was modified. Please try again.",
                "Land Rules (unavailable)",
                java.util.List.of(
                        "Rules cannot be shown right now: land data is not ready yet.",
                        "Please reopen this menu in a moment.",
                        "No setting was changed."));
    }

    /** Visible for tests: the vars one rule head template renders with. */
    static Map<String, Object> headVars(String rule, String explicit, String effective,
            String source) {
        return Map.of("action", rule, "state", explicit == null ? "" : explicit,
                "outcome", effective, "layer", source);
    }
}
