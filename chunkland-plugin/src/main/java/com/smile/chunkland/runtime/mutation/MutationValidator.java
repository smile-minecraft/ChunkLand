package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.Objects;

/**
 * Pure validation of a mutation request. Implementations must be stateless,
 * immutable and must not touch Bukkit, SQL or I/O.
 */
@FunctionalInterface
public interface MutationValidator {

    ValidationResult validate(MutationRequest request);

    record ValidationResult(boolean valid, String diagnosticKey) {
        public ValidationResult {
            if (!valid) Objects.requireNonNull(diagnosticKey, "diagnosticKey");
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult rejected(String diagnosticKey) {
            Objects.requireNonNull(diagnosticKey, "diagnosticKey");
            return new ValidationResult(false, diagnosticKey);
        }
    }
}
