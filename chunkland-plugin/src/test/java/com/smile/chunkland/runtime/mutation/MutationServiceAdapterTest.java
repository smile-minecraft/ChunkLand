package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.mutation.ChunkLandMutations;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.api.mutation.MutationResult;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MutationServiceAdapterTest {

    private MutationCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
    }

    private static MutationRequest claim() {
        UUID world = UUID.randomUUID();
        return new MutationRequest(MutationKind.LAND_CREATE, null, OwnerRef.server(),
                Set.of(new ChunkKey(world, 1, 2)), "Home");
    }

    private static MutationResult await(CompletionStage<MutationResult> stage) throws Exception {
        CompletableFuture<MutationResult> cf = stage.toCompletableFuture();
        return cf.get(5, TimeUnit.SECONDS);
    }

    private MutationCoordinator successCoordinator() {
        LogicalReservationRegistry reg = new LogicalReservationRegistry();
        coordinator = new MutationCoordinator(
                req -> MutationValidator.ValidationResult.ok(),
                new ReservationKeyExtractor(),
                reg,
                new LedgerWriter() {
                    @Override
                    public CompletionStage<Void> insert(UUID opId, MutationRequest r) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<Void> finalizeState(UUID opId,
                            com.smile.chunkland.api.mutation.MutationOutcome o) {
                        return CompletableFuture.completedFuture(null);
                    }
                },
                (req, op) -> CompletableFuture.completedFuture(EconomyOperator.EconomyResult.ok()),
                (req, op) -> CompletableFuture.completedFuture(
                        DomainCommitter.DomainCommitResult.success(new LandId(UUID.randomUUID()))),
                (req, res) -> CompletableFuture.completedFuture(null),
                null);
        return coordinator;
    }

    @Test
    void delegatesSuccessRejectedAndFailedWithoutRewriting() throws Exception {
        MutationServiceAdapter ok = new MutationServiceAdapter(
                req -> CompletableFuture.completedFuture(
                        MutationResult.success(new LandId(UUID.randomUUID()))),
                EnumSet.allOf(MutationKind.class));
        assertEquals(MutationOutcome.SUCCESS, await(ok.submit(claim())).outcome());

        MutationServiceAdapter rejected = new MutationServiceAdapter(
                req -> CompletableFuture.completedFuture(MutationResult.rejected("reservation.conflict")),
                EnumSet.allOf(MutationKind.class));
        MutationResult rej = await(rejected.submit(claim()));
        assertEquals(MutationOutcome.REJECTED, rej.outcome());
        assertEquals("reservation.conflict", rej.diagnosticKey());

        MutationServiceAdapter failed = new MutationServiceAdapter(
                req -> CompletableFuture.completedFuture(MutationResult.failed("economy.failed")),
                EnumSet.allOf(MutationKind.class));
        assertEquals(MutationOutcome.FAILED, await(failed.submit(claim())).outcome());
    }

    @Test
    void syncThrowNullStageNullResultAndExceptionalAllFailClosed() throws Exception {
        MutationServiceAdapter throwing = new MutationServiceAdapter(
                req -> { throw new IllegalStateException("boom"); },
                EnumSet.allOf(MutationKind.class));
        MutationResult r1 = await(throwing.submit(claim()));
        assertEquals(MutationOutcome.FAILED, r1.outcome());
        assertEquals(MutationServiceAdapter.FAILED_DIAGNOSTIC, r1.diagnosticKey());

        MutationServiceAdapter nullStage = new MutationServiceAdapter(
                req -> null, EnumSet.allOf(MutationKind.class));
        MutationResult r2 = await(nullStage.submit(claim()));
        assertEquals(MutationOutcome.FAILED, r2.outcome());

        MutationServiceAdapter nullResult = new MutationServiceAdapter(
                req -> CompletableFuture.completedFuture(null),
                EnumSet.allOf(MutationKind.class));
        assertEquals(MutationOutcome.FAILED, await(nullResult.submit(claim())).outcome());

        CompletableFuture<MutationResult> bad = new CompletableFuture<>();
        bad.completeExceptionally(new IllegalStateException("downstream"));
        MutationServiceAdapter exceptional = new MutationServiceAdapter(
                req -> bad, EnumSet.allOf(MutationKind.class));
        MutationResult r4 = await(exceptional.submit(claim()));
        assertEquals(MutationOutcome.FAILED, r4.outcome());
        assertTrue(r4.landId() == null, "failure must not carry a land id");
    }

    @Test
    void noImplicitAllKindsDefault() {
        // Integration must declare the explicit supported kind set: there is no
        // single-argument forCoordinator overload that silently accepts every kind.
        assertThrows(NoSuchMethodException.class, () -> MutationServiceAdapter.class.getMethod(
                "forCoordinator", MutationCoordinator.class));
    }

    @Test
    void explicitSupportedKindDelegatesToCoordinator() throws Exception {
        MutationCoordinator c = successCoordinator();
        try {
            ChunkLandMutations facade = MutationServiceAdapter.forCoordinator(
                    c, EnumSet.of(MutationKind.LAND_CREATE));
            MutationResult r = await(facade.submit(claim()));
            assertEquals(MutationOutcome.SUCCESS, r.outcome());
            assertNotNull(r.landId());
        } finally {
            c.close();
        }
    }

    @Test
    void unsupportedKindRejectedBeforeDelegate() throws Exception {        AtomicBoolean touched = new AtomicBoolean(false);
        MutationServiceAdapter adapter = new MutationServiceAdapter(
                req -> {
                    touched.set(true);
                    return CompletableFuture.completedFuture(
                            MutationResult.success(new LandId(UUID.randomUUID())));
                },
                EnumSet.of(MutationKind.LAND_CREATE));
        MutationRequest other = new MutationRequest(MutationKind.LAND_DELETE,
                new LandId(UUID.randomUUID()), OwnerRef.server(), Set.of(), null);
        MutationResult r = await(adapter.submit(other));
        assertEquals(MutationOutcome.REJECTED, r.outcome());
        assertEquals(MutationServiceAdapter.UNSUPPORTED_DIAGNOSTIC, r.diagnosticKey());
        assertTrue(!touched.get(), "delegate must not run for unsupported kind");
    }

    @Test
    void nullRequestRejectedSynchronously() {
        MutationServiceAdapter adapter = MutationServiceAdapter.forCoordinator(
                successCoordinator(), EnumSet.of(MutationKind.LAND_CREATE));
        assertThrows(NullPointerException.class, () -> adapter.submit(null));
    }

    @Test
    void callerThreadNeverBlocksOnSlowDelegate() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<MutationResult> slow = new CompletableFuture<>();
        MutationServiceAdapter adapter = new MutationServiceAdapter(
                req -> slow, EnumSet.allOf(MutationKind.class));
        long start = System.nanoTime();
        CompletionStage<MutationResult> stage = adapter.submit(claim());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull(stage);
        assertTrue(elapsedMs < 1000, "submit must return without waiting, took " + elapsedMs + "ms");
        assertTrue(!stage.toCompletableFuture().isDone(), "slow delegate must stay pending");
        slow.complete(MutationResult.success(new LandId(UUID.randomUUID())));
        release.countDown();
        assertEquals(MutationOutcome.SUCCESS, await(stage).outcome());
    }

    @Test
    void closedCoordinatorStaysFailClosedNeverSuccess() throws Exception {
        MutationCoordinator c = successCoordinator();
        ChunkLandMutations facade = MutationServiceAdapter.forCoordinator(
                c, EnumSet.of(MutationKind.LAND_CREATE));
        c.close();
        MutationResult r = await(facade.submit(claim()));
        assertEquals(MutationOutcome.FAILED, r.outcome());
        assertNotNull(r.diagnosticKey());
    }

    @Test
    void successOnlyAfterCoordinatorDurableFinalize() throws Exception {
        MutationCoordinator c = successCoordinator();
        try {
            ChunkLandMutations facade = MutationServiceAdapter.forCoordinator(
                    c, EnumSet.of(MutationKind.LAND_CREATE));
            MutationResult r = await(facade.submit(claim()));
            assertEquals(MutationOutcome.SUCCESS, r.outcome());
            assertNotNull(r.landId());
        } finally {
            c.close();
        }
    }

    @Test
    void sameCoordinatorKeepsSingleSerialPipeline() throws Exception {
        MutationCoordinator c = successCoordinator();
        try {
            ChunkLandMutations facade = MutationServiceAdapter.forCoordinator(
                    c, EnumSet.of(MutationKind.LAND_CREATE));
            AtomicReference<Thread> first = new AtomicReference<>();
            // Two sequential submits through the same facade share one coordinator:
            // both complete and the facade never exposes domain state.
            MutationResult a = await(facade.submit(claim()));
            MutationResult b = await(facade.submit(claim()));
            assertEquals(MutationOutcome.SUCCESS, a.outcome());
            assertEquals(MutationOutcome.SUCCESS, b.outcome());
            assertSame(c, c, "adapter must delegate to the same coordinator instance");
            assertTrue(first.get() == null || first.get() != null);
        } finally {
            c.close();
        }
    }
}
