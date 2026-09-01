package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Domain commit collaborator. Invoked inside a SQL transaction boundary managed
 * by the persistence layer; implementations may rely on DB UNIQUE as a last line
 * of defence and should signal conflict via {@link DomainCommitResult#conflict}.
 */
@FunctionalInterface
public interface DomainCommitter {

    CompletionStage<DomainCommitResult> commit(MutationRequest request, UUID operationId);

    record DomainCommitResult(boolean success, LandId landId, String diagnosticKey, boolean conflict) {
        public static DomainCommitResult success(LandId landId) {
            Objects.requireNonNull(landId, "landId");
            return new DomainCommitResult(true, landId, null, false);
        }

        public static DomainCommitResult rejected(String diagnosticKey) {
            Objects.requireNonNull(diagnosticKey, "diagnosticKey");
            return new DomainCommitResult(false, null, diagnosticKey, true);
        }

        public static DomainCommitResult failed(String diagnosticKey) {
            Objects.requireNonNull(diagnosticKey, "diagnosticKey");
            return new DomainCommitResult(false, null, diagnosticKey, false);
        }
    }
}
