package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.mutation.MutationRequest;
import java.util.concurrent.CompletionStage;

/**
 * Publishes the durable commit to the volatile runtime index.
 * Implementations must publish an immutable snapshot via a single volatile write.
 */
@FunctionalInterface
public interface MutationPublisher {

    CompletionStage<Void> publish(MutationRequest request, DomainCommitter.DomainCommitResult commitResult);
}
