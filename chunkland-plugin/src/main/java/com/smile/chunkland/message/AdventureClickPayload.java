package com.smile.chunkland.message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-version reader for click payload text.
 *
 * <p>Adventure 4 exposes the payload as {@code ClickEvent.value()}, while
 * Adventure 5 moved the text behind {@code ClickEvent.payload()} whose result
 * carries {@code value()}. A direct call compiles against paper-api 26.1.2
 * but throws {@code NoSuchMethodError} on Folia 26.2 for every message
 * carrying a click, so this helper reads through reflection only and never
 * links against either shape. Lookup order is {@code value()} first
 * (Adventure 4), then {@code payload()} followed by the payload's
 * {@code value()} (Adventure 5 text payload).
 *
 * <p>When neither shape yields text — null event, null payload, non-text
 * payload, or any reflective failure — the result is null and nothing is
 * thrown; callers keep their existing null handling unchanged.
 */
public final class AdventureClickPayload {

    private static final Map<Class<?>, Method> VALUE_METHODS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Method> PAYLOAD_METHODS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Method> ACTION_METHODS = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Method> NAME_METHODS = new ConcurrentHashMap<>();

    private static final Map<String, Field> ACTION_CONSTANT_FIELDS = new ConcurrentHashMap<>();

    /** Action name compared by the compat layer instead of constant fields. */
    static final String RUN_COMMAND = "RUN_COMMAND";

    /** Action name compared by the compat layer instead of constant fields. */
    static final String SUGGEST_COMMAND = "SUGGEST_COMMAND";

    private AdventureClickPayload() {
    }

    /**
     * Cross-version reader for the click action name.
     *
     * <p>Adventure 4 declares the action constants with the plain action type,
     * while Adventure 5 narrows each constant field to its own subtype, so a
     * direct constant reference compiles against paper-api 26.1.2 but throws
     * {@code NoSuchFieldError} on Folia 26.2. The {@code action()} method
     * itself is compatible on both versions, and the returned action object
     * carries {@code name()} on both, so this helper compares the name string
     * instead of linking against any constant field.
     *
     * @param clickEvent click event of either Adventure shape; null yields null
     * @return action name such as {@code "RUN_COMMAND"}, or null when it
     *     cannot be obtained; never throws
     */
    public static String actionName(Object clickEvent) {
        if (clickEvent == null) {
            return null;
        }
        try {
            Method actionMethod = lookup(clickEvent.getClass(), ACTION_METHODS, "action");
            if (actionMethod == null) {
                return null;
            }
            Object action = actionMethod.invoke(clickEvent);
            if (action == null) {
                return null;
            }
            if (action instanceof String text) {
                return text;
            }
            Method nameMethod = lookup(action.getClass(), NAME_METHODS, "name");
            if (nameMethod != null) {
                Object name = nameMethod.invoke(action);
                if (name instanceof String text) {
                    return text;
                }
            }
            return action.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Whether the click carries the run-command action.
     *
     * <p>Two paths, neither linking any constant field: first the reflective
     * constant object is compared by identity or equality, then the raw action
     * name is normalised (underscores removed, upper-cased) and compared, so
     * the 4.x enum name and the 5.x lowercase or camel-case forms all match.
     * Anything unrecognised stays false and the caller fails closed.
     *
     * @param clickEvent click event of either Adventure shape; null yields false
     * @return true only for the run-command action; never throws
     */
    public static boolean isRunCommand(Object clickEvent) {
        return matchesAction(clickEvent, RUN_COMMAND);
    }

    /**
     * Whether the click carries the suggest-command action.
     *
     * <p>Two paths, neither linking any constant field: first the reflective
     * constant object is compared by identity or equality, then the raw action
     * name is normalised (underscores removed, upper-cased) and compared, so
     * the 4.x enum name and the 5.x lowercase or camel-case forms all match.
     * Anything unrecognised stays false and the caller fails closed.
     *
     * @param clickEvent click event of either Adventure shape; null yields false
     * @return true only for the suggest-command action; never throws
     */
    public static boolean isSuggestCommand(Object clickEvent) {
        return matchesAction(clickEvent, SUGGEST_COMMAND);
    }

    private static boolean matchesAction(Object clickEvent, String constant) {
        Object action = readAction(clickEvent);
        if (action == null) {
            return false;
        }
        Object expected = actionConstant(action.getClass(), constant);
        if (expected != null && (action == expected || action.equals(expected))) {
            return true;
        }
        String rawName = rawActionName(action);
        return rawName != null && normalizeActionName(constant).equals(normalizeActionName(rawName));
    }

    private static Object readAction(Object clickEvent) {
        if (clickEvent == null) {
            return null;
        }
        try {
            Method actionMethod = lookup(clickEvent.getClass(), ACTION_METHODS, "action");
            if (actionMethod == null) {
                return null;
            }
            return actionMethod.invoke(clickEvent);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static String rawActionName(Object action) {
        try {
            if (action instanceof String text) {
                return text;
            }
            Method nameMethod = lookup(action.getClass(), NAME_METHODS, "name");
            if (nameMethod != null) {
                Object name = nameMethod.invoke(action);
                if (name instanceof String text) {
                    return text;
                }
            }
            return action.toString();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static Object actionConstant(Class<?> actionType, String constant) {
        String key = actionType.getName() + '#' + constant;
        Field cached = ACTION_CONSTANT_FIELDS.get(key);
        if (cached != null) {
            return readConstant(cached);
        }
        try {
            Field found = actionType.getField(constant);
            if (Modifier.isStatic(found.getModifiers())) {
                Field raced = ACTION_CONSTANT_FIELDS.putIfAbsent(key, found);
                return readConstant(raced != null ? raced : found);
            }
        } catch (NoSuchFieldException | SecurityException ignored) {
            // No such constant on this shape; fall through to the name path.
        }
        return null;
    }

    private static Object readConstant(Field field) {
        try {
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeActionName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                continue;
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    /**
     * Reads the click payload text without linking against either Adventure shape.
     *
     * @param clickEvent click event of either Adventure shape; null yields null
     * @return payload text, or null when it cannot be obtained; never throws
     */
    public static String read(Object clickEvent) {
        if (clickEvent == null) {
            return null;
        }
        String direct = readValueAccessor(clickEvent);
        if (direct != null) {
            return direct;
        }
        // A value() accessor exists but held null (Adventure 4 with no payload);
        // there is nothing further to read from this shape.
        if (hasAccessor(clickEvent.getClass(), VALUE_METHODS, "value")) {
            return null;
        }
        try {
            Method payloadMethod = lookup(clickEvent.getClass(), PAYLOAD_METHODS, "payload");
            if (payloadMethod == null) {
                return null;
            }
            Object payload = payloadMethod.invoke(clickEvent);
            if (payload == null) {
                return null;
            }
            if (payload instanceof String text) {
                return text;
            }
            return readValueAccessor(payload);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static String readValueAccessor(Object target) {
        try {
            Method valueMethod = lookup(target.getClass(), VALUE_METHODS, "value");
            if (valueMethod == null) {
                return null;
            }
            Object value = valueMethod.invoke(target);
            return value instanceof String text ? text : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasAccessor(Class<?> type, Map<Class<?>, Method> cache, String name) {
        return lookup(type, cache, name) != null;
    }

    private static Method lookup(Class<?> type, Map<Class<?>, Method> cache, String name) {
        Method cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        Method found = null;
        try {
            Method candidate = type.getMethod(name);
            if (candidate.getParameterCount() == 0) {
                candidate.setAccessible(true);
                found = candidate;
            }
        } catch (NoSuchMethodException | SecurityException ignored) {
            found = null;
        }
        if (found != null) {
            Method raced = cache.putIfAbsent(type, found);
            return raced != null ? raced : found;
        }
        return null;
    }
}
