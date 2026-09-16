package com.smile.chunkland.event;

/**
 * A public Pre event vetoed the pending mutation.
 *
 * <p>Carries the stable {@link #DIAGNOSTIC_KEY} diagnostic key so command
 * handlers reply with a fixed message key instead of rendering listener
 * state. Throwing (or returning) this shape means the mutation stopped
 * before any Economy, durable, or audit side effect.
 */
public final class PublicEventCancelledException extends IllegalStateException {

    /** Stable key for the cancelled reply. */
    public static final String DIAGNOSTIC_KEY = "event.cancelled";

    /** Cancelled without a cause: the veto itself is the reason. */
    public PublicEventCancelledException() {
        super(DIAGNOSTIC_KEY);
    }

    /** Stable key for the cancelled reply. */
    public String diagnosticKey() {
        return DIAGNOSTIC_KEY;
    }
}
