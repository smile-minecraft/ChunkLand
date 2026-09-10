package com.smile.chunkland.persistence;

/**
 * Rejection for a Global Group write that must not happen.
 *
 * <p>The machine-readable {@code reason} travels to the command reply so the
 * sender sees why the write was refused; no partial row is ever left behind.
 * Reasons are stable message suffixes ({@code group.invalid},
 * {@code group.duplicate}, {@code group.unknown}, {@code group.not_allowed},
 * {@code group.restricted}).
 */
public class GroupRejectedException extends RuntimeException {

    private final String reason;

    public GroupRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public GroupRejectedException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** Machine-readable refusal reason, also used as the reply suffix. */
    public String reason() {
        return reason;
    }
}
