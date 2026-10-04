package com.smile.chunkland.gui;

import java.util.Map;

/**
 * Caller-injected visible copy for the member and ban roster pages.
 *
 * <p>The roster pages never look up a language service: they ask this seam
 * for a language key plus variables and get plain display text back. The
 * production source renders the key through the message pipeline;
 * {@link #english()} is the bundled wording used when no source is wired or
 * a lookup fails, so the pages always compose.
 */
@FunctionalInterface
public interface ManagementRosterTexts {

    String MEMBERS_ENTRY_NAME = "gui.manage.roster.members.entry_name";
    String MEMBERS_ENTRY_HINT = "gui.manage.roster.members.entry_hint";
    String MEMBERS_TITLE = "gui.manage.roster.members.title";
    String MEMBERS_REMOVE_LINE = "gui.manage.roster.members.remove_line";
    String MEMBERS_ADD_NAME = "gui.manage.roster.members.add_name";
    String MEMBERS_ADD_HINT = "gui.manage.roster.members.add_hint";
    String MEMBERS_EMPTY = "gui.manage.roster.members.empty";
    String MEMBERS_PICKER_TITLE = "gui.manage.roster.members.picker_title";
    String MEMBERS_PICK_LINE = "gui.manage.roster.members.pick_line";
    String BANS_ENTRY_NAME = "gui.manage.roster.bans.entry_name";
    String BANS_ENTRY_HINT = "gui.manage.roster.bans.entry_hint";
    String BANS_TITLE = "gui.manage.roster.bans.title";
    String BANS_REMOVE_LINE = "gui.manage.roster.bans.remove_line";
    String BANS_ADD_NAME = "gui.manage.roster.bans.add_name";
    String BANS_ADD_HINT = "gui.manage.roster.bans.add_hint";
    String BANS_EMPTY = "gui.manage.roster.bans.empty";
    String BANS_PICKER_TITLE = "gui.manage.roster.bans.picker_title";
    String BANS_PICK_LINE = "gui.manage.roster.bans.pick_line";
    String PICKER_EMPTY = "gui.manage.roster.picker_empty";
    String PICKER_EMPTY_HINT = "gui.manage.roster.picker_empty_hint";
    String PREVIOUS = "gui.manage.roster.previous";
    String NEXT = "gui.manage.roster.next";
    String UNKNOWN_PLAYER = "gui.manage.roster.unknown_player";

    /** Shared with the permission pages so every back button reads alike. */
    String BACK = "gui.manage.details.back_name";

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
    static ManagementRosterTexts english() {
        return English.INSTANCE;
    }

    /** Bundled English wording, used without a text source or when one fails. */
    final class English implements ManagementRosterTexts {

        static final English INSTANCE = new English();

        private static final Map<String, String> TEMPLATES = Map.ofEntries(
                Map.entry(MEMBERS_ENTRY_NAME, "Members"),
                Map.entry(MEMBERS_ENTRY_HINT,
                        "See, add or remove the players trusted on this land."),
                Map.entry(MEMBERS_TITLE, "Land Members (<count>)"),
                Map.entry(MEMBERS_REMOVE_LINE, "Click to untrust this player."),
                Map.entry(MEMBERS_ADD_NAME, "Add a member"),
                Map.entry(MEMBERS_ADD_HINT, "Pick an online player to trust."),
                Map.entry(MEMBERS_EMPTY, "Nobody is trusted on this land yet"),
                Map.entry(MEMBERS_PICKER_TITLE, "Choose a player to trust"),
                Map.entry(MEMBERS_PICK_LINE, "Click to trust this player."),
                Map.entry(BANS_ENTRY_NAME, "Banned players"),
                Map.entry(BANS_ENTRY_HINT,
                        "See, add or lift bans that keep players out of this land."),
                Map.entry(BANS_TITLE, "Banned Players (<count>)"),
                Map.entry(BANS_REMOVE_LINE, "Click to unban this player."),
                Map.entry(BANS_ADD_NAME, "Ban a player"),
                Map.entry(BANS_ADD_HINT, "Pick an online player to ban."),
                Map.entry(BANS_EMPTY, "Nobody is banned from this land"),
                Map.entry(BANS_PICKER_TITLE, "Choose a player to ban"),
                Map.entry(BANS_PICK_LINE, "Click to ban this player."),
                Map.entry(PICKER_EMPTY, "No online player to choose"),
                Map.entry(PICKER_EMPTY_HINT, "For offline players use the chat command."),
                Map.entry(PREVIOUS, "Previous page"),
                Map.entry(NEXT, "Next page"),
                Map.entry(UNKNOWN_PLAYER, "Unknown player (<value>)"),
                Map.entry(BACK, "Back"));

        private English() {
        }

        @Override
        public String text(String key, Map<String, Object> vars) {
            if (key == null) {
                return "";
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
