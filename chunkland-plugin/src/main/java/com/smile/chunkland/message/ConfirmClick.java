package com.smile.chunkland.message;

import com.smile.chunkland.api.land.LandName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Safe carrier for the claim and delete confirmation clicks.
 *
 * <p>MiniMessage placeholders inside a single-quoted click argument stay
 * literal through the production parser, so the template alone can never
 * produce an executable command. After the parser renders the body text, this
 * helper stamps the click with validated vars, building the command value as
 * a plain string instead of parsing it as MiniMessage.
 *
 * <p>Claim confirmations stamp a {@code run_command} click with the validated
 * generation, revision and land name; names pass through the shared land-name
 * contract, so control characters and blank input fail closed before any click
 * exists. Delete confirmations stamp a {@code suggest_command} click carrying
 * only the validated revision, so a single misclick fills the chat box instead
 * of deleting. Stamping always preserves the template's click action and never
 * upgrades a suggest into a run.
 */
public final class ConfirmClick {

    /** Message key whose click carries the confirmation token pair. */
    public static final String CONFIRM_KEY = "land.claim.confirm";

    /**
     * Legacy alias with no producer and no lang resource; kept only to honor
     * the existing compatibility contract, never widened or narrowed.
     */
    public static final String CLAIM_CONFIRM_COMMAND_KEY = "command.land.claim.confirm";

    /** Message key whose click carries the delete confirmation revision. */
    public static final String DELETE_CONFIRM_KEY = "command.land.delete.confirm";

    /** Message keys whose click may carry the confirmation token pair. */
    public static final Set<String> CONFIRM_KEYS = Set.of(CONFIRM_KEY, CLAIM_CONFIRM_COMMAND_KEY, DELETE_CONFIRM_KEY);

    /** Command prefix stamped into the click payload. */
    public static final String COMMAND_PREFIX = "/land confirm ";

    /** Exact literal click template stamped in the lang resources. */
    static final String CLICK_TEMPLATE = "/land confirm <generation> <revision> <land_name>";

    /** Command prefix stamped into the delete click payload. */
    public static final String DELETE_COMMAND_PREFIX = "/land delete confirm ";

    /** Exact literal delete click template stamped in the lang resources. */
    static final String DELETE_CLICK_TEMPLATE = "/land delete confirm <revision>";

    private ConfirmClick() {
    }

    /**
     * Whether the given message key owns the confirmation click.
     *
     * @param messageKey key asking for a rewrite; null is never a confirm key
     */
    public static boolean isConfirmKey(String messageKey) {
        return CONFIRM_KEY.equals(messageKey) || CLAIM_CONFIRM_COMMAND_KEY.equals(messageKey)
                || DELETE_CONFIRM_KEY.equals(messageKey);
    }

    /**
     * Key-gated rewrite: only an explicit confirm message key is stamped.
     * Any other key passes through untouched — its clicks are never rewritten.
     *
     * @param rendered parser output for the given key; never null
     * @param vars original render vars holding generation, revision and land name
     * @param messageKey key that produced the component; non-confirm keys pass through
     * @return component with executable confirmation clicks for confirm keys,
     *     otherwise the input unchanged; never null
     * @throws MessageException when a confirm key has no confirmation click to stamp
     */
    public static Component rewriteConfirmClick(Component rendered, Map<String, Object> vars, String messageKey) {
        Objects.requireNonNull(rendered, "rendered");
        if (!isConfirmKey(messageKey)) {
            return rendered;
        }
        if (DELETE_CONFIRM_KEY.equals(messageKey)) {
            return rewriteDeleteConfirmClick(rendered, vars);
        }
        return rewriteConfirmClick(rendered, vars);
    }

    /**
     * Rewrite confirmation clicks in an already-rendered component.
     *
     * @param rendered parser output for {@code CONFIRM_KEY}; never null
     * @param vars original render vars holding generation, revision and land name
     * @return component with executable confirmation clicks; never null
     * @throws MessageException when vars are missing or unsafe
     */
    public static Component rewriteConfirmClick(Component rendered, Map<String, Object> vars) {
        Objects.requireNonNull(rendered, "rendered");
        String command = buildCommand(vars);
        RewriteResult result = rewrite(rendered, command);
        if (!result.touched()) {
            throw new MessageException(CONFIRM_KEY,
                    "confirm template has no confirmation click to stamp");
        }
        return result.component();
    }

    /**
     * Rewrite delete confirmation clicks in an already-rendered component.
     *
     * <p>Only {@code suggest_command} clicks carrying the delete template are
     * stamped; any other action passes through untouched so a delete prompt can
     * never gain a single-click executable command.
     *
     * @param rendered parser output for {@code DELETE_CONFIRM_KEY}; never null
     * @param vars original render vars holding the revision
     * @return component with the suggested delete command; never null
     * @throws MessageException when vars are missing or unsafe
     */
    public static Component rewriteDeleteConfirmClick(Component rendered, Map<String, Object> vars) {
        Objects.requireNonNull(rendered, "rendered");
        String command = buildDeleteCommand(vars);
        RewriteResult result = rewriteDelete(rendered, command);
        if (!result.touched()) {
            throw new MessageException(DELETE_CONFIRM_KEY,
                    "delete confirm template has no suggestion click to stamp");
        }
        return result.component();
    }

    /**
     * Build the suggested delete command from validated vars.
     *
     * <p>Only the revision participates; generation and land name are never
     * part of the delete command.
     *
     * @throws MessageException when the revision is missing, non-numeric, or unsafe
     */
    public static String buildDeleteCommand(Map<String, Object> vars) {
        if (vars == null) {
            throw new MessageException(DELETE_CONFIRM_KEY, "delete confirm vars must carry revision");
        }
        long revision = parseToken(DELETE_CONFIRM_KEY, vars.get("revision"), "revision");
        return DELETE_COMMAND_PREFIX + revision;
    }

    /**
     * Build the executable command from validated vars.
     *
     * @throws MessageException when any token is missing, non-numeric, or unsafe
     */
    public static String buildCommand(Map<String, Object> vars) {
        if (vars == null) {
            throw new MessageException(CONFIRM_KEY, "confirm vars must carry generation, revision and land_name");
        }
        long generation = parseToken(vars.get("generation"), "generation");
        long revision = parseToken(vars.get("revision"), "revision");
        Object rawName = vars.get("land_name");
        if (rawName == null) {
            throw new MessageException(CONFIRM_KEY, "confirm vars must carry land_name");
        }
        String name = String.valueOf(rawName);
        try {
            LandName.normalize(name);
        } catch (RuntimeException invalid) {
            throw new MessageException(CONFIRM_KEY, "confirm land_name is not usable", invalid);
        }
        if (name.isBlank()) {
            throw new MessageException(CONFIRM_KEY, "confirm land_name must not be blank");
        }
        return COMMAND_PREFIX + generation + " " + revision + " " + name;
    }

    private static long parseToken(Object raw, String field) {
        return parseToken(CONFIRM_KEY, raw, field);
    }

    private static long parseToken(String messageKey, Object raw, String field) {
        if (raw == null) {
            throw new MessageException(messageKey, "confirm vars must carry " + field);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new MessageException(messageKey, "confirm " + field + " must not be blank");
        }
        long parsed;
        try {
            parsed = Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw new MessageException(messageKey, "confirm " + field + " must be numeric", invalid);
        }
        if (parsed < 0) {
            throw new MessageException(messageKey, "confirm " + field + " must not be negative");
        }
        return parsed;
    }

    private record RewriteResult(Component component, boolean touched) {
    }

    private static RewriteResult rewrite(Component node, String command) {
        List<Component> children = node.children();
        List<Component> rewrittenChildren = new ArrayList<>(children.size());
        boolean childTouched = false;
        for (Component child : children) {
            RewriteResult nested = rewrite(child, command);
            rewrittenChildren.add(nested.component());
            childTouched = childTouched || nested.touched();
        }
        Component current = node;
        if (!rewrittenChildren.equals(children)) {
            current = current.children(rewrittenChildren);
        }
        ClickEvent click = current.clickEvent();
        if (click != null && isConfirmClick(click.value())) {
            ClickEvent stamped = stampPreservingAction(click, command);
            if (stamped != null) {
                current = current.clickEvent(stamped);
                return new RewriteResult(current, true);
            }
        }
        return new RewriteResult(current, childTouched);
    }

    private static RewriteResult rewriteDelete(Component node, String command) {
        List<Component> children = node.children();
        List<Component> rewrittenChildren = new ArrayList<>(children.size());
        boolean childTouched = false;
        for (Component child : children) {
            RewriteResult nested = rewriteDelete(child, command);
            rewrittenChildren.add(nested.component());
            childTouched = childTouched || nested.touched();
        }
        Component current = node;
        if (!rewrittenChildren.equals(children)) {
            current = current.children(rewrittenChildren);
        }
        ClickEvent click = current.clickEvent();
        if (click != null
                && click.action() == ClickEvent.Action.SUGGEST_COMMAND
                && isDeleteConfirmClick(click.value())) {
            current = current.clickEvent(ClickEvent.suggestCommand(command));
            return new RewriteResult(current, true);
        }
        return new RewriteResult(current, childTouched);
    }

    /**
     * Restamp the click with the validated command while keeping its action,
     * so a suggest template is never upgraded into an executable run. Actions
     * outside run/suggest are left alone (the caller then fails closed).
     */
    private static ClickEvent stampPreservingAction(ClickEvent click, String command) {
        if (click.action() == ClickEvent.Action.RUN_COMMAND) {
            return ClickEvent.runCommand(command);
        }
        if (click.action() == ClickEvent.Action.SUGGEST_COMMAND) {
            return ClickEvent.suggestCommand(command);
        }
        return null;
    }

    private static boolean isConfirmClick(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.equals(CLICK_TEMPLATE)) {
            return true;
        }
        if (!trimmed.startsWith(COMMAND_PREFIX)) {
            return false;
        }
        String rest = trimmed.substring(COMMAND_PREFIX.length()).trim();
        String[] parts = rest.split("\\s+", 3);
        return parts.length == 3
                && isNonNegativeLong(parts[0])
                && isNonNegativeLong(parts[1])
                && !parts[2].isBlank();
    }

    private static boolean isDeleteConfirmClick(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.equals(DELETE_CLICK_TEMPLATE)) {
            return true;
        }
        if (!trimmed.startsWith(DELETE_COMMAND_PREFIX)) {
            return false;
        }
        String rest = trimmed.substring(DELETE_COMMAND_PREFIX.length()).trim();
        return isNonNegativeLong(rest);
    }

    private static boolean isNonNegativeLong(String text) {
        try {
            return Long.parseLong(text) >= 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }
}
