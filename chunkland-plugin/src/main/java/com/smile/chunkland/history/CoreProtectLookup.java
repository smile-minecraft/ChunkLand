package com.smile.chunkland.history;

import com.smile.chunkland.api.history.HistoryEntry;
import com.smile.chunkland.api.history.HistoryQuery;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Reflective fetch against the optional block-logging backend.
 *
 * <p>Every backend type is resolved by name at call time, so this class
 * compiles and loads with no optional dependency on any classpath — the only
 * backend literal lives inside a method-call string. The caller passes an
 * already-captured immutable {@link HistoryQuery}; this fetch resolves the
 * world by name (a fast metadata read — a missing world yields an empty
 * outcome, a failing read throws so the provider degrades to unavailable),
 * runs the bounded {@code performLookup} plus per-row {@code parseResult},
 * and maps rows to redacted entries.
 *
 * <p>Mapping rules: rows that are not string arrays, unparsable rows, rows
 * with blank materials or negative timestamps, and rows from another world
 * are skipped; attribution and raw payloads are never read. Collection stops
 * at the queried limit and marks the outcome truncated when further valid
 * rows remain. A disabled backend or an unexpected method shape throws, so
 * the provider answers unavailable instead of guessing.
 */
final class CoreProtectLookup {

    /** Reflective backend entry point; kept as a call-time string only. */
    private static final String LOOKUP_METHOD = "performLookup";

    private static final String PARSE_METHOD = "parseResult";

    private static final String ENABLED_METHOD = "isEnabled";

    private CoreProtectLookup() {
    }

    /**
     * @param api backend handle from discovery (never null)
     * @param server Bukkit server for the world-name read (never null)
     * @param query captured immutable query (never null)
     * @return bounded outcome, possibly empty
     * @throws IllegalStateException when the backend is absent, disabled,
     *                              shape-mismatched or the lookup itself fails
     */
    static CoreProtectHistoryProvider.LookupOutcome fetch(Object api,
                                                         Server server,
                                                         HistoryQuery query) {
        if (api == null || server == null || query == null) {
            throw new IllegalStateException("backend lookup unavailable");
        }
        ensureEnabled(api);
        World world = readWorld(server, query.worldName());
        if (world == null) {
            return new CoreProtectHistoryProvider.LookupOutcome(List.of(), false);
        }
        Location center = new Location(world,
                query.centerX(), query.centerY(), query.centerZ());
        List<?> rows = performLookup(api, query, center);
        Method parse = parseMethod(api);
        List<HistoryEntry> entries = new ArrayList<>();
        int validRows = 0;
        for (Object row : rows) {
            if (!(row instanceof String[] raw)) {
                continue;
            }
            HistoryEntry entry = parseRow(parse, api, raw, query.worldName());
            if (entry == null) {
                continue;
            }
            validRows++;
            if (entries.size() < query.maxResults()) {
                entries.add(entry);
            }
        }
        return new CoreProtectHistoryProvider.LookupOutcome(
                entries, validRows > query.maxResults());
    }

    private static void ensureEnabled(Object api) {
        Method enabled = methodNamed(api, ENABLED_METHOD);
        Object value = invoke(enabled, api);
        if (!Boolean.TRUE.equals(value)) {
            throw new IllegalStateException("backend lookup unavailable");
        }
    }

    private static World readWorld(Server server, String worldName) {
        try {
            return server.getWorld(worldName);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("backend lookup unavailable", failure);
        }
    }

    private static List<?> performLookup(Object api, HistoryQuery query, Location center) {
        Method lookup;
        try {
            lookup = api.getClass().getMethod(LOOKUP_METHOD,
                    int.class, List.class, List.class, List.class, List.class, List.class,
                    int.class, Location.class);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("backend lookup unavailable", missing);
        }
        Object result = invoke(lookup, api,
                query.secondsBack(), null, null, null, null, List.of(0, 1),
                query.radiusBlocks(), center);
        if (!(result instanceof List<?> rows)) {
            throw new IllegalStateException("backend lookup unavailable");
        }
        return rows;
    }

    private static Method parseMethod(Object api) {
        try {
            return api.getClass().getMethod(PARSE_METHOD, String[].class);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("backend lookup unavailable", missing);
        }
    }

    private static HistoryEntry parseRow(Method parse, Object api, String[] raw,
                                         String worldName) {
        Object parsed;
        try {
            parsed = parse.invoke(api, (Object) raw);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
        if (parsed == null) {
            return null;
        }
        try {
            if (!worldMatches(parsed, worldName)) {
                return null;
            }
            int x = readInt(parsed, "getX");
            int y = readInt(parsed, "getY");
            int z = readInt(parsed, "getZ");
            String material = Objects.toString(readObject(parsed, "getType"), "").strip();
            if (material.isEmpty()) {
                return null;
            }
            long timestamp = readLong(parsed, "getTimestamp");
            if (timestamp < 0) {
                return null;
            }
            String action = readAction(parsed);
            if (action == null) {
                return null;
            }
            return new HistoryEntry(x, y, z, action, material, timestamp);
        } catch (ReflectiveOperationException | RuntimeException skipped) {
            return null;
        }
    }

    private static boolean worldMatches(Object parsed, String worldName)
            throws ReflectiveOperationException {
        Method world;
        try {
            world = parsed.getClass().getMethod("worldName");
        } catch (NoSuchMethodException absent) {
            return true;
        }
        Object value = invoke(world, parsed);
        String rowWorld = Objects.toString(value, "").strip();
        return rowWorld.isEmpty() || rowWorld.equalsIgnoreCase(worldName);
    }

    private static String readAction(Object parsed) throws ReflectiveOperationException {
        try {
            Method actionString = parsed.getClass().getMethod("getActionString");
            String action = Objects.toString(invoke(actionString, parsed), "").strip();
            if (!action.isEmpty()) {
                return action;
            }
        } catch (NoSuchMethodException absent) {
            // Fall through to the numeric action id.
        }
        try {
            Method actionId = parsed.getClass().getMethod("getActionId");
            Object value = invoke(actionId, parsed);
            if (value instanceof Number id) {
                return switch (id.intValue()) {
                    case 0 -> "removed";
                    case 1 -> "placed";
                    case 2 -> "interaction";
                    default -> null;
                };
            }
        } catch (NoSuchMethodException absent) {
            // No action detail available: the row stays skipped.
        }
        return null;
    }

    private static int readInt(Object parsed, String getter)
            throws ReflectiveOperationException {
        Object value = readObject(parsed, getter);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("backend row unreadable: " + getter);
    }

    private static long readLong(Object parsed, String getter)
            throws ReflectiveOperationException {
        Object value = readObject(parsed, getter);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("backend row unreadable: " + getter);
    }

    private static Object readObject(Object parsed, String getter)
            throws ReflectiveOperationException {
        return invoke(methodNamed(parsed, getter), parsed);
    }

    private static Method methodNamed(Object target, String name) {
        try {
            return target.getClass().getMethod(name);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("backend lookup unavailable", missing);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("backend lookup unavailable",
                    failure.getCause());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("backend lookup unavailable", failure);
        }
    }
}
