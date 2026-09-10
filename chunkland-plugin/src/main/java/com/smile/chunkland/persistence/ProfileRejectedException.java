package com.smile.chunkland.persistence;

/**
 * Rejection for a Permission Profile write that must not happen.
 *
 * <p>The machine-readable {@code reason} travels to the command reply so the
 * sender sees why the write was refused; no partial row is ever left behind.
 * Reasons are stable message suffixes ({@code profile.invalid},
 * {@code profile.reserved}, {@code profile.duplicate},
 * {@code profile.unknown}, {@code profile.invalid_permission},
 * {@code profile.invalid_state}, {@code profile.not_allowed},
 * {@code profile.restricted}).
 */
public class ProfileRejectedException extends RuntimeException {

    private final String reason;

    public ProfileRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public ProfileRejectedException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** Machine-readable refusal reason, also used as the reply suffix. */
    public String reason() {
        return reason;
    }
}
