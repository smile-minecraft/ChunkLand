package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Functional callback fired once per successful {@link ConfigService#reload()}.
 *
 * <p>This is the minimum seam that downstream consumers (e.g. the Selection
 * Session invalidator planned for a later milestone) need to observe a config change.
 * The listener is invoked <strong>after</strong> the new snapshot has been
 * atomically published, so callbacks always observe a consistent epoch
 * pair (old → new) and never a half-applied state.</p>
 *
 * <p>Implementations are expected to be fast and non-throwing; throwing from a
 * listener will not roll back the reload (the contract is "reload already
 * committed"), but it will be reported as a {@link RuntimeException} from
 * {@link ConfigService#reload()} so the caller can surface it.</p>
 */
@FunctionalInterface
public interface ConfigReloadListener {

    void onConfigReload(ReloadDiff diff);

    /** Convenience adapter for callers that want to be sure they got the seam. */
    static ConfigReloadListener noop() {
        return diff -> Objects.requireNonNull(diff, "diff");
    }
}
