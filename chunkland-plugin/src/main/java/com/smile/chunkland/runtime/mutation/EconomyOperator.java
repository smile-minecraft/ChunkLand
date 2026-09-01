package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Economy collaborator. Implementations must not be called inside a SQL transaction.
 * The coordinator guarantees that no SQL transaction is active when {@link #charge} is invoked.
 */
@FunctionalInterface
public interface EconomyOperator {

    CompletionStage<EconomyResult> charge(MutationRequest request, UUID operationId);

    record EconomyResult(boolean success, String diagnosticKey) {
        public static EconomyResult ok() {
            return new EconomyResult(true, null);
        }

        public static EconomyResult failed(String diagnosticKey) {
            Objects.requireNonNull(diagnosticKey, "diagnosticKey");
            return new EconomyResult(false, diagnosticKey);
        }
    }
}
