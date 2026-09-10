package com.smile.chunkland.persistence;

/**
 * Rejection for a Land/SubLand binding write that must not happen.
 *
 * <p>The machine-readable {@code reason} travels to the command reply so the
 * sender sees why the write was refused; no partial row is ever left behind.
 * Reasons are stable message suffixes ({@code binding.invalid},
 * {@code binding.unknown}, {@code binding.reserved}, {@code binding.failed}).
 */
public class BindingRejectedException extends RuntimeException {

    private final String reason;

    public BindingRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public BindingRejectedException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** Machine-readable refusal reason, also used as the reply suffix. */
    public String reason() {
        return reason;
    }
}
