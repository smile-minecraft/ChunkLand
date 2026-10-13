package com.smile.chunkland.config;

import java.util.Objects;

/**
 * Distinguishable outcome of one {@link ConfigService#tryReload(ReloadGate)}
 * attempt.
 *
 * <p>The legacy {@link ConfigService#reload()} cannot tell "nothing changed"
 * from "loading failed" (both hand back the previous snapshot silently), so
 * the admin reload path uses this value instead. Every kind carries the
 * snapshot the service holds afterwards, so callers can always keep reading.
 */
public final class ReloadOutcome {

    /** What one reload attempt decided. */
    public enum Kind {
        /** A new snapshot was validated, published and listeners notified. */
        SUCCESS,
        /** The file parsed to content identical to the live snapshot. */
        NO_CHANGE,
        /** A hot-swap gate refused the candidate; restart is required. */
        REJECTED,
        /** Loading or validation failed; the previous snapshot is kept. */
        FAILED,
        /** Another reload already holds the reload lock. */
        BUSY,
    }

    private final Kind kind;
    private final ChunkLandConfig snapshot;
    private final ReloadDiff diff;
    private final RuntimeException callbackFailure;
    private final String reason;
    private final Throwable cause;

    private ReloadOutcome(Kind kind,
                          ChunkLandConfig snapshot,
                          ReloadDiff diff,
                          RuntimeException callbackFailure,
                          String reason,
                          Throwable cause) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.diff = diff;
        this.callbackFailure = callbackFailure;
        this.reason = reason;
        this.cause = cause;
    }

    /**
     * A committed reload. {@code callbackFailure} is the first listener
     * failure when at least one post-publish callback threw; the snapshot
     * is already live then, so callers must report "committed but a callback
     * failed" and never pretend a rollback happened.
     */
    public static ReloadOutcome success(ChunkLandConfig snapshot,
                                        ReloadDiff diff,
                                        RuntimeException callbackFailure) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(diff, "diff");
        return new ReloadOutcome(Kind.SUCCESS, snapshot, diff, callbackFailure, null, null);
    }

    /** The candidate parsed to the same content; nothing was published. */
    public static ReloadOutcome noChange(ChunkLandConfig snapshot) {
        return new ReloadOutcome(Kind.NO_CHANGE, snapshot, null, null, null, null);
    }

    /** The gate refused the candidate; the operator must restart. */
    public static ReloadOutcome rejected(ChunkLandConfig snapshot, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ReloadOutcome(Kind.REJECTED, snapshot, null, null, reason, null);
    }

    /** Loading or validation failed; the previous snapshot is untouched. */
    public static ReloadOutcome failed(ChunkLandConfig snapshot, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return new ReloadOutcome(Kind.FAILED, snapshot, null, null, cause.getMessage(), cause);
    }

    /** Another reload is already in progress; this attempt did nothing. */
    public static ReloadOutcome busy(ChunkLandConfig snapshot) {
        return new ReloadOutcome(Kind.BUSY, snapshot, null, null,
                "another reload is already in progress", null);
    }

    public Kind kind() {
        return kind;
    }

    /** Snapshot the service holds after this attempt. Never null. */
    public ChunkLandConfig snapshot() {
        return snapshot;
    }

    /** Diff of a successful publish; null for every other kind. */
    public ReloadDiff diff() {
        return diff;
    }

    /**
     * First post-publish listener failure, or null when every callback
     * succeeded. Only ever set on {@link Kind#SUCCESS}.
     */
    public RuntimeException callbackFailure() {
        return callbackFailure;
    }

    /** Human reason for {@link Kind#REJECTED}, {@link Kind#FAILED} and {@link Kind#BUSY}. */
    public String reason() {
        return reason;
    }

    /** Loader failure for {@link Kind#FAILED}; null otherwise. */
    public Throwable cause() {
        return cause;
    }
}
