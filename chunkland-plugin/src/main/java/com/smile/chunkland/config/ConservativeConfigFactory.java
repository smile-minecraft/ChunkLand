package com.smile.chunkland.config;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conservative in-memory defaults: the floor a damaged or vanished config
 * falls back to when no last-known-good copy exists.
 *
 * <p>The snapshot is built in memory and never written to disk. Every listed
 * world is unclaimable full-height protection
 * ({@code claim-enabled: false}, {@code FULL_HEIGHT}); every subject default
 * stays {@code INHERIT}, which resolves fail-closed to {@code DENY}; every
 * rule default — including the one built-in {@code ALLOW}
 * ({@code PASSIVE_MOB_SPAWN}) — is pinned to {@code DENY} so the built-in
 * layer is unreachable; pricing is unavailable ({@code economy == null}), so
 * priced operations fail closed downstream instead of running on a zero
 * table. Other sections keep their schema defaults; with new claims denied
 * they are unreachable on the protection path.
 *
 * <p>Worlds created after startup are absent from the map on purpose: the
 * conservative lookup and claim wirings fail those closed explicitly, so this
 * factory never has to guess about them.
 */
public final class ConservativeConfigFactory {

    private ConservativeConfigFactory() {
        // utility class
    }

    /**
     * Build the conservative snapshot for exactly these world names.
     *
     * @param worldNames server world names at startup; {@code null} behaves
     *                   like an empty collection, blank names are skipped
     */
    public static ChunkLandConfig forWorlds(Collection<String> worldNames) {
        Map<String, WorldSettings> worlds = new LinkedHashMap<>();
        if (worldNames != null) {
            for (String name : worldNames) {
                if (name == null || name.isBlank() || worlds.containsKey(name)) {
                    continue;
                }
                worlds.put(name, new WorldSettings(false, VerticalMode.FULL_HEIGHT));
            }
        }
        EnumMap<LandRuleType, PermissionState> rules = new EnumMap<>(LandRuleType.class);
        for (LandRuleType rule : LandRuleType.values()) {
            rules.put(rule, PermissionState.DENY);
        }
        RuleDefaultsConfig ruleDefaults = new RuleDefaultsConfig(rules, Map.of());
        return new ChunkLandConfig(worlds,
                LimitSettings.defaults(),
                MessageSettings.defaults(),
                SelectionSettings.defaults(),
                SubjectDefaultsConfig.empty(),
                ruleDefaults,
                null,
                AuditSettings.defaults(),
                0L,
                ConfigSchema.deriveWorldEpochs(worlds),
                4096);
    }
}
