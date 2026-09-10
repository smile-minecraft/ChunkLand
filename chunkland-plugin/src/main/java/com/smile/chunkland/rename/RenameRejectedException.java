package com.smile.chunkland.rename;

import java.util.Objects;

/**
 * Fail-closed rename rejection carrying the diagnostic key reported to the
 * sender. Thrown before any write (invalid input, unknown land, denied
 * actor) and from inside the mutation transaction (stale owner, duplicate
 * name key), so both layers speak one vocabulary.
 */
public final class RenameRejectedException extends RuntimeException {

    private final String diagnosticKey;

    public RenameRejectedException(String diagnosticKey) {
        super(diagnosticKey);
        this.diagnosticKey = Objects.requireNonNull(diagnosticKey, "diagnosticKey");
    }

    public RenameRejectedException(String diagnosticKey, Throwable cause) {
        super(diagnosticKey, cause);
        this.diagnosticKey = Objects.requireNonNull(diagnosticKey, "diagnosticKey");
    }

    /** Machine-readable reason such as {@code rename.duplicate}. */
    public String diagnosticKey() {
        return diagnosticKey;
    }
}
