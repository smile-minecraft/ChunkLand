package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Stable public facade over the existing serialized mutation pipeline.
 *
 * <p>Delegates to the single serial {@link MutationCoordinator} without
 * touching domain state directly, without blocking the caller, and without
 * bypassing validation, reservation, ledger, economy, domain commit, publish
 * or finalize ordering. All of those guarantees stay inside the coordinator;
 * this adapter only adds the stable fail-closed boundary.
 *
 * <p>Fail-closed policy: an unsupported kind completes with
 * {@code REJECTED / mutation.unsupported} before the delegate is touched, so
 * no reservation or side effect is created for kinds the envelope cannot
 * safely describe. The supported set is always declared explicitly by the
 * integration: there is no default that silently accepts every kind. A
 * synchronous throw, {@code null} stage, {@code null} result or exceptional
 * completion from the delegate completes with
 * {@code FAILED / mutation.failed} and never propagates. A {@code null}
 * request is rejected synchronously with {@link NullPointerException}.
 *
 * <p>Contract-only scope: this adapter is an injected bridge over an
 * explicitly provided delegate. No default plugin instance is installed by
 * this task; production assembly and the supported kind set belong to the
 * integration.
 */
public final class MutationServiceAdapter implements ChunkLandMutations {

    /** Stable diagnostic for kinds the envelope cannot safely describe. */
    public static final String UNSUPPORTED_DIAGNOSTIC = "mutation.unsupported";

    /** Stable diagnostic for delegate transport failures. */
    public static final String FAILED_DIAGNOSTIC = "mutation.failed";

    private final Function<MutationRequest, CompletionStage<MutationResult>> delegate;
    private final Set<MutationKind> supported;

    /**
     * @param delegate never {@code null}; invoked on the caller thread without blocking
     * @param supported kinds the envelope may describe; defensively copied, never {@code null}
     */
    public MutationServiceAdapter(
            Function<MutationRequest, CompletionStage<MutationResult>> delegate,
            Set<MutationKind> supported) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(supported, "supported");
        this.supported = EnumSet.copyOf(supported.isEmpty()
                ? EnumSet.noneOf(MutationKind.class)
                : EnumSet.copyOf(supported));
    }

    /**
     * Adapt an injected coordinator. The adapter keeps the coordinator's
     * single serial executor and full pipeline ordering.
     *
     * @param coordinator never {@code null}
     * @param supported kinds to accept, declared explicitly by the integration;
     *     defensively copied
     * @return facade delegating to {@code coordinator::submit}
     */
    public static MutationServiceAdapter forCoordinator(
            MutationCoordinator coordinator, Set<MutationKind> supported) {
        Objects.requireNonNull(coordinator, "coordinator");
        return new MutationServiceAdapter(coordinator::submit, supported);
    }

    @Override
    public CompletionStage<MutationResult> submit(MutationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!supported.contains(request.kind())) {
            return CompletableFuture.completedFuture(MutationResult.rejected(UNSUPPORTED_DIAGNOSTIC));
        }
        CompletionStage<MutationResult> stage;
        try {
            stage = delegate.apply(request);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(MutationResult.failed(FAILED_DIAGNOSTIC));
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(MutationResult.failed(FAILED_DIAGNOSTIC));
        }
        CompletableFuture<MutationResult> out = new CompletableFuture<>();
        try {
            stage.handle((result, failure) -> {
                if (failure != null || result == null) {
                    out.complete(MutationResult.failed(FAILED_DIAGNOSTIC));
                } else {
                    out.complete(result);
                }
                return null;
            });
        } catch (Throwable t) {
            out.complete(MutationResult.failed(FAILED_DIAGNOSTIC));
        }
        return out;
    }
}
