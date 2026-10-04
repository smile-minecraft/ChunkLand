package com.smile.chunkland.gui;

import java.util.Locale;
import java.util.Map;

/**
 * Caller-injected visible copy for the Bedrock management forms.
 *
 * <p>The form factory never looks up a language service: it asks this seam
 * for a language key plus variables and gets plain display text back. The
 * production source renders the key through the message pipeline in the
 * player's locale; {@link #english()} is the bundled wording used when no
 * source is wired or a lookup fails, so a form always opens.
 */
@FunctionalInterface
public interface BedrockFormTexts {

    String ROOT_TITLE = "bedrock.manage.root.title";
    String ROOT_CONTENT = "bedrock.manage.root.content";
    String MENU_PREFIX = "bedrock.manage.menu.";
    String TRUST_TITLE = "bedrock.manage.trust.title";
    String TRUST_CONTENT = "bedrock.manage.trust.content";
    String TRUST_ADD = "bedrock.manage.trust.add";
    String TRUST_REMOVE = "bedrock.manage.trust.remove";
    String BAN_TITLE = "bedrock.manage.ban.title";
    String BAN_CONTENT = "bedrock.manage.ban.content";
    String BAN_ADD = "bedrock.manage.ban.add";
    String BAN_REMOVE = "bedrock.manage.ban.remove";
    String CHOICE_TITLE = "bedrock.manage.choice.title";
    String CHOICE_CONTENT = "bedrock.manage.choice.content";
    String CHOICE_EMPTY_TITLE = "bedrock.manage.choice.empty_title";
    String CHOICE_EMPTY_CONTENT = "bedrock.manage.choice.empty_content";
    String DELETE_TITLE = "bedrock.manage.delete.title";
    String DELETE_CONTENT = "bedrock.manage.delete.content";
    String DELETE_CONFIRM = "bedrock.manage.delete.confirm";
    String DELETE_CANCEL = "bedrock.manage.delete.cancel";

    /** Shared with the Java management GUI so both screens read alike. */
    String BACK = "gui.manage.details.back_name";
    String UNAVAILABLE_TITLE = "gui.manage.unavailable.title";
    String UNAVAILABLE_CONTENT = "gui.manage.remedy.unavailable";
    String DETAILS_TITLE = "gui.manage.details.title";
    String ROW_HEAD = "gui.manage.row.head";
    String ROW_CONFLICT_SUFFIX = "gui.manage.row.conflict_suffix";
    String ROW_REMEDY_LINE = "gui.manage.row.remedy_line";
    String REMEDY_CONFLICT = "gui.manage.remedy.conflict";
    String ACTION_PREFIX = "permission.action.";

    /**
     * Plain display text for one key.
     *
     * @param key language key; never {@code null}
     * @param vars template variables; never {@code null}
     * @return the text to show; never {@code null}
     */
    String text(String key, Map<String, Object> vars);

    /** Plain display text for a key without variables. */
    default String text(String key) {
        return text(key, Map.of());
    }

    /** Bundled English wording; never fails. */
    static BedrockFormTexts english() {
        return English.INSTANCE;
    }

    /** Bundled English wording, used without a text source or when one fails. */
    final class English implements BedrockFormTexts {

        static final English INSTANCE = new English();

        private static final Map<String, String> TEMPLATES = Map.ofEntries(
                Map.entry(ROOT_TITLE, "Land Management"),
                Map.entry(ROOT_CONTENT,
                        "Choose a management step. Some steps continue in chat."),
                Map.entry(MENU_PREFIX + "claim", "Claim land"),
                Map.entry(MENU_PREFIX + "expand", "Expand land"),
                Map.entry(MENU_PREFIX + "shrink", "Shrink land"),
                Map.entry(MENU_PREFIX + "trust_untrust", "Trust members"),
                Map.entry(MENU_PREFIX + "ban_unban", "Ban management"),
                Map.entry(MENU_PREFIX + "basic_permission", "Permissions"),
                Map.entry(MENU_PREFIX + "subland", "SubLand"),
                Map.entry(MENU_PREFIX + "group", "Groups"),
                Map.entry(MENU_PREFIX + "profile", "Profiles"),
                Map.entry(MENU_PREFIX + "land_rule", "Land rules"),
                Map.entry(MENU_PREFIX + "inspect", "Inspect"),
                Map.entry(MENU_PREFIX + "explain", "Explain"),
                Map.entry(MENU_PREFIX + "delete", "Delete land"),
                Map.entry(TRUST_TITLE, "Trust members"),
                Map.entry(TRUST_CONTENT, "Choose an action. Offline players: use chat instead."),
                Map.entry(TRUST_ADD, "Trust player"),
                Map.entry(TRUST_REMOVE, "Untrust player"),
                Map.entry(BAN_TITLE, "Ban management"),
                Map.entry(BAN_CONTENT, "Choose an action. Offline players: use chat instead."),
                Map.entry(BAN_ADD, "Ban player"),
                Map.entry(BAN_REMOVE, "Unban player"),
                Map.entry(CHOICE_TITLE, "<value> - choose player"),
                Map.entry(CHOICE_CONTENT, "Choose a player for <value>. "
                        + "Offline players: use the chat command instead."),
                Map.entry(CHOICE_EMPTY_TITLE, "<value> - no players online"),
                Map.entry(CHOICE_EMPTY_CONTENT, "No online players right now. "
                        + "For offline players, use the chat command instead."),
                Map.entry(DELETE_TITLE, "Delete land"),
                Map.entry(DELETE_CONTENT, "Delete land <land_name>? This cannot be undone. "
                        + "Confirming runs the usual delete prompt; finish with the "
                        + "/land delete confirm command shown in chat."),
                Map.entry(DELETE_CONFIRM, "Delete"),
                Map.entry(DELETE_CANCEL, "Keep"),
                Map.entry(BACK, "Back"),
                Map.entry(UNAVAILABLE_TITLE, "Land Management (unavailable)"),
                Map.entry(UNAVAILABLE_CONTENT,
                        "Outcome unavailable: data missing or the resolver failed (fail-closed).\n"
                                + "Re-open the view after the snapshot reloads.\n"
                                + "Mutations stay gated; no change was applied."),
                Map.entry(DETAILS_TITLE,
                        "Land Permissions (<deny_count> DENY, <allow_count> ALLOW)"),
                Map.entry(ROW_HEAD, "<action>: <outcome> @ <layer><conflict>"),
                Map.entry(ROW_CONFLICT_SUFFIX, " !CONFLICT"),
                Map.entry(ROW_REMEDY_LINE, "  - <remedy>"),
                Map.entry(REMEDY_CONFLICT,
                        "Remove the DENY binding on <layer> through the existing binding flow "
                                + "(still gated).\n"
                                + "Keep the DENY and remove the conflicting ALLOW on <layer> "
                                + "instead.\n"
                                + "Move the intended ALLOW to a higher-precedence layer: "
                                + "SubLand binding ahead of Land binding, binding ahead of "
                                + "default."));

        private English() {
        }

        @Override
        public String text(String key, Map<String, Object> vars) {
            if (key == null) {
                return "";
            }
            if (key.startsWith(ACTION_PREFIX)) {
                // The bundled wording names an action by its command id.
                return key.substring(ACTION_PREFIX.length()).toUpperCase(Locale.ROOT);
            }
            String template = TEMPLATES.get(key);
            if (template == null) {
                return key;
            }
            if (vars == null || vars.isEmpty()) {
                return template;
            }
            String out = template;
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                out = out.replace("<" + entry.getKey() + ">", String.valueOf(entry.getValue()));
            }
            return out;
        }
    }
}
