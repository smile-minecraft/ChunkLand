package com.smile.chunkland.protection;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Startup-validated routing table from protection action to decision source.
 *
 * <p>The defaults mirror each action's declared source. Validation is
 * fail-closed: a table that misses an action or maps one to {@code null}
 * throws {@link IllegalStateException}, so the plugin refuses to start rather
 * than enforcing a half-wired ruleset. Concrete behaviour interceptors are
 * owned by later milestones; this skeleton only guarantees the routing is
 * complete before any of them runs.
 */
public final class ProtectionActionRegistry {

    private ProtectionActionRegistry() {
    }

    /**
     * @return a mutable table holding every action's declared source.
     */
    public static Map<ProtectionActionType, DecisionSource> defaults() {
        EnumMap<ProtectionActionType, DecisionSource> routes =
                new EnumMap<>(ProtectionActionType.class);
        for (ProtectionActionType action : ProtectionActionType.values()) {
            routes.put(action, action.decisionSource());
        }
        return routes;
    }

    /**
     * Validates the table and returns an unmodifiable copy.
     *
     * @param routes candidate routing table (must not be null)
     * @return unmodifiable validated copy covering every action
     * @throws NullPointerException if the table itself is null
     * @throws IllegalStateException if any action is missing or maps to null
     */
    public static Map<ProtectionActionType, DecisionSource> validated(
            Map<ProtectionActionType, DecisionSource> routes) {
        Objects.requireNonNull(routes, "routes");
        List<String> missing = new ArrayList<>();
        for (ProtectionActionType action : ProtectionActionType.values()) {
            if (routes.get(action) == null) {
                missing.add(action.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Protection registry incomplete; missing decision source for: "
                            + String.join(", ", missing));
        }
        return Collections.unmodifiableMap(new EnumMap<>(routes));
    }
}
