package com.smile.chunkland.adapter;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fail-closed enable policy for ChunkLand, kept server-independent so it can be
 * unit-tested without a live Bukkit server.
 *
 * <p>When AceLib is missing or not ready, ChunkLand must log a warning, release the
 * bridge state, and self-disable — never throw an NPE.</p>
 */
public final class AceLibLifecycle {

    private AceLibLifecycle() {
    }

    /**
     * Attempt to acquire a ready AceLib API and enable ChunkLand.
     *
     * @param bridge        the bridge whose state is populated on success
     * @param resolver      resolves the current AceLib provider
     * @param logger        receives the warning on failure
     * @param disableAction self-disable hook (typically
     *                      {@code server.getPluginManager().disablePlugin(this)})
     * @return {@code true} when a ready API was acquired
     */
    public static boolean enable(AceLibBridge bridge,
                                 AceLibBridge.ProviderResolver resolver,
                                 Logger logger,
                                 Runnable disableAction) {
        if (bridge.acquire(resolver)) {
            return true;
        }
        logger.log(Level.WARNING,
            "ChunkLand 未取得就緒的 AceLib provider（AceLib 可能未安裝或尚未 ready）；安全停用 ChunkLand。");
        bridge.release();
        disableAction.run();
        return false;
    }
}
