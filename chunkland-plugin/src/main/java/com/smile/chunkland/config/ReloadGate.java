package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Hot-swap gate consulted inside {@link ConfigService#tryReload(ReloadGate)}
 * after a candidate loads but before it publishes.
 *
 * <p>The gate runs while the service holds the reload lock, so the
 * {@code previous} snapshot it sees is exactly the one the candidate would
 * replace. Returning {@link GateVerdict#rejectRestart} keeps the previous
 * snapshot untouched: nothing is partially applied.
 */
@FunctionalInterface
public interface ReloadGate {

    /** Decide whether {@code loaded} may replace {@code previous}. */
    GateVerdict check(ChunkLandConfig previous, ChunkLandConfig loaded);

    /** Verdict of one gate check. */
    final class GateVerdict {
        private final boolean allowed;
        private final String reason;

        private GateVerdict(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        /** The candidate may publish. */
        public static GateVerdict allow() {
            return new GateVerdict(true, null);
        }

        /**
         * The candidate must not publish; the operator has to restart for
         * it. {@code reason} names the blocking change for the reply.
         */
        public static GateVerdict rejectRestart(String reason) {
            Objects.requireNonNull(reason, "reason");
            return new GateVerdict(false, reason);
        }

        public boolean allowed() {
            return allowed;
        }

        /** Blocking change for a rejection; null when allowed. */
        public String reason() {
            return reason;
        }
    }
}
