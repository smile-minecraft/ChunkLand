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
    String NAME_INPUT_TITLE = "bedrock.manage.name_input.title";
    String NAME_INPUT_CONTENT = "bedrock.manage.name_input.content";
    String NAME_INPUT_PLACEHOLDER = "bedrock.manage.name_input.placeholder";
    String NAME_INPUT_SUBMIT = "bedrock.manage.name_input.submit";
    String CHOICE_INPUT = "bedrock.manage.choice.input";
    String RULE_LIST_TITLE = "bedrock.manage.rule_list.title";
    String RULE_LIST_CONTENT = "bedrock.manage.rule_list.content";
    String RULE_CONFIRM_TITLE = "bedrock.manage.rule_confirm.title";
    String RULE_CONFIRM_CONTENT = "bedrock.manage.rule_confirm.content";
    String RULE_ALLOW = "bedrock.manage.rule_confirm.allow";
    String RULE_DENY = "bedrock.manage.rule_confirm.deny";
    String RULE_INHERIT = "bedrock.manage.rule_confirm.inherit";
    String RULE_CANCEL = "bedrock.manage.rule_confirm.cancel";
    String PERMISSION_CONFIRM_TITLE = "bedrock.manage.permission_confirm.title";
    String PERMISSION_CONFIRM_CONTENT = "bedrock.manage.permission_confirm.content";
    String CONFIRM_OK = "bedrock.manage.confirm.ok";
    String CONFIRM_CANCEL = "bedrock.manage.confirm.cancel";
    String SUBLAND_TITLE = "bedrock.manage.subland.title";
    String SUBLAND_CONTENT = "bedrock.manage.subland.content";
    String SUBLAND_SELECT = "bedrock.manage.subland.select";
    String SUBLAND_CREATE = "bedrock.manage.subland.create";
    String SUBLAND_UPDATE = "bedrock.manage.subland.update";
    String SUBLAND_DELETE = "bedrock.manage.subland.delete";
    String SUBLAND_EXTEND = "bedrock.manage.subland.extend";
    String SUBLAND_EMPTY_TITLE = "bedrock.manage.subland.empty_title";
    String SUBLAND_EMPTY_CONTENT = "bedrock.manage.subland.empty_content";
    String GROUP_TITLE = "bedrock.manage.group.title";
    String GROUP_CONTENT = "bedrock.manage.group.content";
    String GROUP_CREATE = "bedrock.manage.group.create";
    String GROUP_LIST = "bedrock.manage.group.list";
    String GROUP_ADD = "bedrock.manage.group.add";
    String GROUP_REMOVE = "bedrock.manage.group.remove";
    String GROUP_DELETE = "bedrock.manage.group.delete";
    String GROUP_MEMBER_TITLE = "bedrock.manage.group_member.title";
    String GROUP_MEMBER_CONTENT = "bedrock.manage.group_member.content";
    String GROUP_MEMBER_GROUP_PLACEHOLDER = "bedrock.manage.group_member.group_placeholder";
    String GROUP_MEMBER_PLAYER_PLACEHOLDER = "bedrock.manage.group_member.player_placeholder";
    String GROUP_MEMBER_SUBMIT = "bedrock.manage.group_member.submit";
    String PROFILE_TITLE = "bedrock.manage.profile.title";
    String PROFILE_CONTENT = "bedrock.manage.profile.content";
    String PROFILE_CREATE = "bedrock.manage.profile.create";
    String PROFILE_LIST = "bedrock.manage.profile.list";
    String PROFILE_SET = "bedrock.manage.profile.set";
    String PROFILE_DELETE = "bedrock.manage.profile.delete";
    String PROFILE_SET_TITLE = "bedrock.manage.profile_set.title";
    String PROFILE_SET_CONTENT = "bedrock.manage.profile_set.content";
    String PROFILE_SET_PROFILE_PLACEHOLDER = "bedrock.manage.profile_set.profile_placeholder";
    String PROFILE_SET_ACTION_LABEL = "bedrock.manage.profile_set.action_label";
    String PROFILE_SET_STATE_LABEL = "bedrock.manage.profile_set.state_label";
    String PROFILE_SET_SUBMIT = "bedrock.manage.profile_set.submit";
    String DELETE_CHOICE_TITLE = "bedrock.manage.delete_choice.title";
    String DELETE_CHOICE_CONTENT = "bedrock.manage.delete_choice.content";
    String DELETE_NOW = "bedrock.manage.delete_choice.delete";
    String FORCE_DELETE = "bedrock.manage.delete_choice.force_delete";
    String DELETE_KEEP = "bedrock.manage.delete_choice.keep";
    String ERROR_TITLE = "bedrock.manage.error.title";
    String ERROR_CONTENT = "bedrock.manage.error.content";
    String PAGER_PREV = "bedrock.manage.pager.prev";
    String PAGER_NEXT = "bedrock.manage.pager.next";

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
                Map.entry(TRUST_CONTENT, "Choose an action, or enter a name below. "
                        + "Offline players: use the name entry in the form."),
                Map.entry(TRUST_ADD, "Trust player"),
                Map.entry(TRUST_REMOVE, "Untrust player"),
                Map.entry(BAN_TITLE, "Ban management"),
                Map.entry(BAN_CONTENT, "Choose an action, or enter a name below. "
                        + "Offline players: use the name entry in the form."),
                Map.entry(BAN_ADD, "Ban player"),
                Map.entry(BAN_REMOVE, "Unban player"),
                Map.entry(CHOICE_TITLE, "<value> - choose player"),
                Map.entry(CHOICE_CONTENT, "Choose a player for <value>, "
                        + "or enter a name for offline players."),
                Map.entry(CHOICE_EMPTY_TITLE, "<value> - no players online"),
                Map.entry(CHOICE_EMPTY_CONTENT, "No online players right now. "
                        + "Use the name entry instead; it also finds offline players."),
                Map.entry(CHOICE_INPUT, "Enter a name"),
                Map.entry(NAME_INPUT_TITLE, "<action> - enter name"),
                Map.entry(NAME_INPUT_CONTENT, "Type the player name for <action>. "
                        + "Known offline names work; unknown names are refused."),
                Map.entry(NAME_INPUT_PLACEHOLDER, "Player name"),
                Map.entry(NAME_INPUT_SUBMIT, "Submit"),
                Map.entry(RULE_LIST_TITLE, "Land rules (page <page>/<pages>)"),
                Map.entry(RULE_LIST_CONTENT, "Choose a rule to change. "
                        + "Page <page> of <pages>."),
                Map.entry(RULE_CONFIRM_TITLE, "Rule: <rule>"),
                Map.entry(RULE_CONFIRM_CONTENT, "Rule <rule>: now <explicit> "
                        + "(effective <effective> from <source>). Choose the new value."),
                Map.entry(RULE_ALLOW, "Allow"),
                Map.entry(RULE_DENY, "Deny"),
                Map.entry(RULE_INHERIT, "Inherit"),
                Map.entry(RULE_CANCEL, "Cancel"),
                Map.entry(PERMISSION_CONFIRM_TITLE, "Permission: <action>"),
                Map.entry(PERMISSION_CONFIRM_CONTENT, "Change <action> "
                        + "from <current> to <target>?"),
                Map.entry(CONFIRM_OK, "Confirm"),
                Map.entry(CONFIRM_CANCEL, "Cancel"),
                Map.entry(SUBLAND_TITLE, "SubLands"),
                Map.entry(SUBLAND_CONTENT, "Choose a SubLand step. "
                        + "Creation needs an active selection first."),
                Map.entry(SUBLAND_SELECT, "Start selection here"),
                Map.entry(SUBLAND_CREATE, "Create SubLand"),
                Map.entry(SUBLAND_UPDATE, "Update SubLand"),
                Map.entry(SUBLAND_DELETE, "Delete SubLand"),
                Map.entry(SUBLAND_EXTEND, "Extend SubLand"),
                Map.entry(SUBLAND_EMPTY_TITLE, "No active selection"),
                Map.entry(SUBLAND_EMPTY_CONTENT, "There is no active selection "
                        + "to work on. Start a selection first, then come back."),
                Map.entry(GROUP_TITLE, "Groups"),
                Map.entry(GROUP_CONTENT, "Choose a group step. "
                        + "Groups live outside lands and outlive them."),
                Map.entry(GROUP_CREATE, "Create group"),
                Map.entry(GROUP_LIST, "List groups"),
                Map.entry(GROUP_ADD, "Add member"),
                Map.entry(GROUP_REMOVE, "Remove member"),
                Map.entry(GROUP_DELETE, "Delete group"),
                Map.entry(GROUP_MEMBER_TITLE, "<action> - choose group and player"),
                Map.entry(GROUP_MEMBER_CONTENT, "Type the group and the player "
                        + "for <action>."),
                Map.entry(GROUP_MEMBER_GROUP_PLACEHOLDER, "Group name or id"),
                Map.entry(GROUP_MEMBER_PLAYER_PLACEHOLDER, "Player name or id"),
                Map.entry(GROUP_MEMBER_SUBMIT, "Submit"),
                Map.entry(PROFILE_TITLE, "Permission profiles"),
                Map.entry(PROFILE_CONTENT, "Choose a profile step. "
                        + "Profiles live outside lands and outlive them."),
                Map.entry(PROFILE_CREATE, "Create profile"),
                Map.entry(PROFILE_LIST, "List profiles"),
                Map.entry(PROFILE_SET, "Set profile entry"),
                Map.entry(PROFILE_DELETE, "Delete profile"),
                Map.entry(PROFILE_SET_TITLE, "Set profile entry"),
                Map.entry(PROFILE_SET_CONTENT, "Type the profile, then pick "
                        + "the permission and the new value."),
                Map.entry(PROFILE_SET_PROFILE_PLACEHOLDER, "Profile name or id"),
                Map.entry(PROFILE_SET_ACTION_LABEL, "Permission"),
                Map.entry(PROFILE_SET_STATE_LABEL, "Value"),
                Map.entry(PROFILE_SET_SUBMIT, "Submit"),
                Map.entry(DELETE_CHOICE_TITLE, "Delete <target>?"),
                Map.entry(DELETE_CHOICE_CONTENT, "Delete <target>? A plain "
                        + "delete is refused while anything still uses it; "
                        + "force delete removes those uses as well."),
                Map.entry(DELETE_NOW, "Delete"),
                Map.entry(FORCE_DELETE, "Delete with references"),
                Map.entry(DELETE_KEEP, "Keep"),
                Map.entry(ERROR_TITLE, "Something did not work"),
                Map.entry(ERROR_CONTENT, "The step could not continue: <reason>. "
                        + "Go back and try again."),
                Map.entry(PAGER_PREV, "Previous"),
                Map.entry(PAGER_NEXT, "Next"),
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
