package com.smile.chunkland.config;

import java.util.Map;
import java.util.Objects;

/**
 * Pure hot-swap policy for the admin reload path: which snapshot differences
 * may publish live, and which refuse the whole candidate.
 *
 * <p>Hot fields apply on the next read after publish: the {@code limits}
 * quotas, the {@code messages} settings, the {@code selection} sessions and
 * budgets, the {@code subject-defaults} and {@code rule-defaults} namespaces,
 * the {@code feedback} tuning, the decision-cache budget, and
 * {@code claim-enabled} of already-listed worlds.
 *
 * <p>Not hot, so any difference refuses the entire candidate (never a
 * partial apply): the world set (added or removed worlds), any world's
 * {@code vertical-mode}, the {@code economy} section, and the {@code audit}
 * section.
 *
 * <p>Bukkit-free on purpose: both snapshots are parameters, so the whole
 * policy is unit-testable without a server.
 */
public final class ConfigReloadPolicy {

    private ConfigReloadPolicy() {
    }

    /**
     * Compare {@code loaded} against {@code previous}.
     *
     * @return allow when only hot fields differ; otherwise the first blocking
     *         change as a restart-required rejection
     */
    public static ReloadGate.GateVerdict check(ChunkLandConfig previous, ChunkLandConfig loaded) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(loaded, "loaded");

        Map<String, WorldSettings> prevWorlds = previous.worlds();
        Map<String, WorldSettings> nextWorlds = loaded.worlds();
        if (!prevWorlds.keySet().equals(nextWorlds.keySet())) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "worlds changed (added or removed worlds); restart to apply");
        }
        for (Map.Entry<String, WorldSettings> entry : prevWorlds.entrySet()) {
            WorldSettings before = entry.getValue();
            WorldSettings after = nextWorlds.get(entry.getKey());
            if (after == null) {
                return ReloadGate.GateVerdict.rejectRestart(
                        "worlds changed (removed world " + entry.getKey() + "); restart to apply");
            }
            if (before.verticalMode() != after.verticalMode()) {
                return ReloadGate.GateVerdict.rejectRestart(
                        "world " + entry.getKey() + " vertical-mode changed; restart to apply");
            }
        }
        if (!Objects.equals(previous.economy(), loaded.economy())) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "economy changed; restart to apply");
        }
        if (!Objects.equals(previous.audit(), loaded.audit())) {
            return ReloadGate.GateVerdict.rejectRestart(
                    "audit changed; restart to apply");
        }
        return ReloadGate.GateVerdict.allow();
    }
}
