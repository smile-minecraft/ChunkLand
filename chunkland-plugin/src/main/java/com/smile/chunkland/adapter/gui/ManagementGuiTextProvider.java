package com.smile.chunkland.adapter.gui;

import com.smile.chunkland.gui.ManagementGuiTexts;
import com.smile.chunkland.gui.ManagementRosterTexts;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Caller-side builder for the injected management-GUI texts.
 *
 * <p>This is where translation happens: every slot resolves a
 * {@code gui.manage.*} template for the player's locale through the
 * message pipeline and arrives in {@code gui/} as plain display text.
 * The framework package itself never sees a language key. Any resolution
 * failure falls back to the bundled English defaults, so a missing
 * pipeline or a malformed template can never break page construction —
 * the GUI still opens, only in the fallback wording.</p>
 *
 * <p>The confirm-toggle follow-up reads {@link ManagementGuiTexts#defaultLore()}
 * and {@link ManagementGuiTexts#confirmTexts()} from the value returned
 * here; page composition does not render those slots yet.</p>
 */
public final class ManagementGuiTextProvider {

    public static final String ROOT_TITLE_KEY = "gui.manage.root.title";
    public static final String ROOT_ENTRY_NAME_KEY = "gui.manage.root.entry_name";
    public static final String ROOT_ENTRY_HINT_KEY = "gui.manage.root.entry_hint";
    public static final String DETAILS_TITLE_KEY = "gui.manage.details.title";
    public static final String DETAILS_BACK_NAME_KEY = "gui.manage.details.back_name";
    public static final String ROW_HEAD_KEY = "gui.manage.row.head";
    public static final String ROW_CONFLICT_SUFFIX_KEY = "gui.manage.row.conflict_suffix";
    public static final String ROW_REMEDY_LINE_KEY = "gui.manage.row.remedy_line";
    public static final String ROW_DEFAULT_LORE_KEY = "gui.manage.row.default_lore";
    public static final String ROW_DEFAULT_UNSET_KEY = "gui.manage.row.default_unset";
    public static final String ROW_READONLY_LINE_KEY = "gui.manage.row.readonly_line";
    public static final String ROW_TOGGLE_LINE_KEY = "gui.manage.row.toggle_line";
    public static final String REMEDY_UNAVAILABLE_KEY = "gui.manage.remedy.unavailable";
    public static final String REMEDY_CONFLICT_KEY = "gui.manage.remedy.conflict";
    public static final String REMEDY_PLAIN_KEY = "gui.manage.remedy.plain";
    public static final String UNAVAILABLE_TITLE_KEY = "gui.manage.unavailable.title";
    public static final String CONFIRM_TITLE_KEY = "gui.manage.confirm.title";
    public static final String CONFIRM_NAME_KEY = "gui.manage.confirm.confirm_name";
    public static final String CANCEL_NAME_KEY = "gui.manage.confirm.cancel_name";
    public static final String CONFIRM_BACK_NAME_KEY = "gui.manage.confirm.back_name";
    public static final String CONFIRM_TARGET_LINE_KEY = "gui.manage.confirm.target_line";
    public static final String CONFIRM_FAILED_LINE_KEY = "gui.manage.confirm.failed_line";

    private final GuiTemplateRenderer renderer;
    private final Function<UUID, Locale> localeLookup;

    /**
     * @param renderer template source; {@code null} reads as fallback-only
     *     (no pipeline), which still returns usable English texts
     * @param localeLookup preferred locale per player; {@code null} reads
     *     as the default chain. Must never block; may return {@code null}.
     */
    public ManagementGuiTextProvider(GuiTemplateRenderer renderer,
            Function<UUID, Locale> localeLookup) {
        this.renderer = renderer;
        this.localeLookup = localeLookup == null ? uuid -> null : localeLookup;
    }

    /**
     * Production entry: rendering goes through the message pipeline's
     * broadcast path (override locale, then the configured default) and
     * plain-serializes, because GUI items paint literal text.
     */
    public static ManagementGuiTextProvider withPipeline(ChunkLandMessagePipeline pipeline,
            Function<UUID, Locale> localeLookup) {
        Objects.requireNonNull(pipeline, "pipeline");
        GuiTemplateRenderer renderer = (key, vars, locale) ->
                PlainTextComponentSerializer.plainText().serialize(
                        pipeline.renderForBroadcast(key, vars, locale));
        return new ManagementGuiTextProvider(renderer, localeLookup);
    }

    /**
     * Builds the injected texts for one viewer. Never returns
     * {@code null} and never throws: any failure yields the bundled
     * English fallback.
     */
    public ManagementGuiTexts resolve(UUID playerUuid) {
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

    /**
     * Builds the roster-page texts for one viewer over the same template
     * source and locale as {@link #resolve}. Never returns {@code null}
     * and never throws: a key that fails to render falls back to the
     * bundled English wording for that key alone.
     */
    public ManagementRosterTexts rosterTexts(UUID playerUuid) {
        GuiTemplateRenderer source = this.renderer;
        if (source == null) {
            return ManagementRosterTexts.english();
        }
        Locale locale = localeOf(playerUuid);
        return (key, vars) -> {
            Map<String, Object> safeVars = vars == null ? Map.of() : vars;
            return renderOr(source, key, safeVars, locale,
                    ManagementRosterTexts.english().text(key, safeVars));
        };
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

    private static ManagementGuiTexts resolveWith(GuiTemplateRenderer renderer, Locale locale) {
        ManagementGuiTexts fallback = fallbackTexts();
        String rootTitle = renderOr(renderer, ROOT_TITLE_KEY, Map.of(), locale,
                fallback.rootTitle());
        String entryName = renderOr(renderer, ROOT_ENTRY_NAME_KEY, Map.of(), locale,
                fallback.entryName());
        List<String> entryLines = List.of(renderOr(renderer, ROOT_ENTRY_HINT_KEY, Map.of(),
                locale, fallback.entryLines().isEmpty() ? "" : fallback.entryLines().get(0)));
        String unavailableTitle = renderOr(renderer, UNAVAILABLE_TITLE_KEY, Map.of(), locale,
                fallback.unavailableTitle());
        List<String> unavailableLines = splitLines(renderOr(renderer, REMEDY_UNAVAILABLE_KEY,
                Map.of(), locale, String.join("\n", fallback.unavailableLines())));
        String backName = renderOr(renderer, DETAILS_BACK_NAME_KEY, Map.of(), locale,
                fallback.backName());
        ManagementGuiTexts.DetailsTitle detailsTitle = (deny, allow) -> renderOr(renderer,
                DETAILS_TITLE_KEY,
                Map.of("deny_count", deny, "allow_count", allow), locale,
                fallback.detailsTitle().title(deny, allow));
        ManagementGuiTexts.RowHead rowHead = (action, outcome, layer, conflict) -> {
            String suffix = conflict
                    ? renderOr(renderer, ROW_CONFLICT_SUFFIX_KEY, Map.of(), locale, " !CONFLICT")
                    : "";
            return renderOr(renderer, ROW_HEAD_KEY,
                    Map.of("action", action, "outcome", outcome,
                            "layer", layer, "conflict", suffix),
                    locale, fallback.rowHead().head(action, outcome, layer, conflict));
        };
        ManagementGuiTexts.RowRemedies rowRemedies = (layer, outcome, conflict) -> {
            String key = conflict ? REMEDY_CONFLICT_KEY : REMEDY_PLAIN_KEY;
            List<String> plain = splitLines(renderOr(renderer, key,
                    Map.of("layer", layer, "outcome", outcome), locale,
                    String.join("\n", fallback.rowRemedies().lore(layer, outcome, conflict))));
            List<String> wrapped = new ArrayList<>(plain.size());
            for (String remedy : plain) {
                wrapped.add(renderOr(renderer, ROW_REMEDY_LINE_KEY, Map.of("remedy", remedy),
                        locale, "  - " + remedy));
            }
            return List.copyOf(wrapped);
        };
        ManagementGuiTexts.DefaultLore defaultLore = (action, state) -> {
            String shown = state == null
                    ? renderOr(renderer, ROW_DEFAULT_UNSET_KEY, Map.of(), locale,
                            "Unset (inherits global)")
                    : state;
            return renderOr(renderer, ROW_DEFAULT_LORE_KEY,
                    Map.of("action", action, "state", shown), locale,
                    fallback.defaultLore().line(action, state));
        };
        String readOnlyLine = renderOr(renderer, ROW_READONLY_LINE_KEY, Map.of(), locale,
                fallback.readOnlyLine());
        String toggleLine = renderOr(renderer, ROW_TOGGLE_LINE_KEY, Map.of(), locale,
                fallback.toggleLine());
        ManagementGuiTexts.ConfirmTexts confirmTexts = new ManagementGuiTexts.ConfirmTexts(
                action -> renderOr(renderer, CONFIRM_TITLE_KEY, Map.of("action", action),
                        locale, fallback.confirmTexts().title().title(action)),
                renderOr(renderer, CONFIRM_NAME_KEY, Map.of(), locale,
                        fallback.confirmTexts().confirmName()),
                renderOr(renderer, CANCEL_NAME_KEY, Map.of(), locale,
                        fallback.confirmTexts().cancelName()),
                renderOr(renderer, CONFIRM_BACK_NAME_KEY, Map.of(), locale,
                        fallback.confirmTexts().backName()),
                state -> renderOr(renderer, CONFIRM_TARGET_LINE_KEY,
                        Map.of("state", state == null ? "" : state), locale,
                        fallback.confirmTexts().targetLine().line(state)),
                renderOr(renderer, CONFIRM_FAILED_LINE_KEY, Map.of(), locale,
                        fallback.confirmTexts().failedLine()));
        return new ManagementGuiTexts(rootTitle, entryName, entryLines, unavailableTitle,
                unavailableLines, backName, detailsTitle, rowHead, rowRemedies, defaultLore,
                confirmTexts, readOnlyLine, toggleLine);
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

    private static List<String> splitLines(String block) {
        if (block == null || block.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Bundled English defaults: the fail-closed wording used when no
     * pipeline is available or a template fails. These literals live on
     * the caller side on purpose — the framework package carries none.
     */
    public static ManagementGuiTexts fallbackTexts() {
        return new ManagementGuiTexts(
                "Land Management",
                "Open the permission detail view.",
                List.of("Open the permission detail view."),
                "Land Management (unavailable)",
                List.of(
                        "Outcome unavailable: data missing or the resolver failed (fail-closed).",
                        "Re-open the view after the snapshot reloads.",
                        "Mutations stay gated; no change was applied."),
                "Back",
                (deny, allow) -> "Land Permissions (" + deny + " DENY, " + allow + " ALLOW)",
                (action, outcome, layer, conflict) -> action + ": " + outcome + " @ " + layer
                        + (conflict ? " !CONFLICT" : ""),
                (layer, outcome, conflict) -> conflict
                        ? List.of(
                                "  - Remove the DENY binding on " + layer
                                        + " through the existing binding flow (still gated).",
                                "  - Keep the DENY and remove the conflicting ALLOW on " + layer
                                        + " instead.",
                                "  - Move the intended ALLOW to a higher-precedence layer: "
                                        + "SubLand binding ahead of Land binding, binding ahead of default.")
                        : List.of(
                                "  - No conflict: " + layer + " decides " + outcome + ".",
                                "  - To change it, update " + layer
                                        + " through the existing management flows (still gated).",
                                "  - Leave defaults INHERIT to inherit; "
                                        + "missing values deny fail-closed."),
                (action, state) -> "Land default " + action + ": "
                        + (state == null ? "Unset (inherits global)" : state),
                new ManagementGuiTexts.ConfirmTexts(
                        action -> "Confirm permission change: " + action,
                        "Confirm",
                        "Cancel",
                        "Back",
                        state -> "Target default: "
                                + (state == null ? "Unset (inherits global)" : state),
                        "Change not applied (fail-closed). No state was modified."),
                "View only in this menu.",
                "Click to switch this default.");
    }
}
