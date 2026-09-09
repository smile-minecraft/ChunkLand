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
 * Safe carrier for the claim confirmation click.
 *
 * <p>MiniMessage placeholders inside a single-quoted click argument stay
 * literal through the production parser, so the template alone can never
 * produce an executable command. After the parser renders the body text, this
 * helper stamps the click with the validated generation, revision and land
 * name, building the run_command value as a plain string instead of parsing
 * the name as MiniMessage. Names pass through the shared land-name contract,
 * so control characters and blank input fail closed before any click exists.
 */
public final class ConfirmClick {

    /** Message key whose click carries the confirmation token pair. */
    public static final String CONFIRM_KEY = "land.claim.confirm";

    /** Alternate confirm message key accepted by the rewrite. */
    public static final String CLAIM_CONFIRM_COMMAND_KEY = "command.land.claim.confirm";

    /** Message keys whose click may carry the confirmation token pair. */
    public static final Set<String> CONFIRM_KEYS = Set.of(CONFIRM_KEY, CLAIM_CONFIRM_COMMAND_KEY);

    /** Command prefix stamped into the click payload. */
    public static final String COMMAND_PREFIX = "/land confirm ";

    /** Exact literal click template stamped in the lang resources. */
    static final String CLICK_TEMPLATE = "/land confirm <generation> <revision> <land_name>";

    private ConfirmClick() {
    }

    /**
     * Whether the given message key owns the confirmation click.
     *
     * @param messageKey key asking for a rewrite; null is never a confirm key
     */
    public static boolean isConfirmKey(String messageKey) {
        return CONFIRM_KEY.equals(messageKey) || CLAIM_CONFIRM_COMMAND_KEY.equals(messageKey);
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
        if (raw == null) {
            throw new MessageException(CONFIRM_KEY, "confirm vars must carry " + field);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            throw new MessageException(CONFIRM_KEY, "confirm " + field + " must not be blank");
        }
        long parsed;
        try {
            parsed = Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw new MessageException(CONFIRM_KEY, "confirm " + field + " must be numeric", invalid);
        }
        if (parsed < 0) {
            throw new MessageException(CONFIRM_KEY, "confirm " + field + " must not be negative");
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
            current = current.clickEvent(ClickEvent.runCommand(command));
            return new RewriteResult(current, true);
        }
        return new RewriteResult(current, childTouched);
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

    private static boolean isNonNegativeLong(String text) {
        try {
            return Long.parseLong(text) >= 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }
}
