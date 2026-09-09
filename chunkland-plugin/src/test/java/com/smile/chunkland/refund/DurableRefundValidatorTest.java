package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.persistence.ChunkRepository;
import com.smile.chunkland.persistence.LandRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the durable refund validator with stub repositories: the
 * refund amount is always the durable basis times the ratio, and every
 * fail-closed path rejects without touching Economy.
 */
class DurableRefundValidatorTest {

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    static final class StubLands implements LandRepository {
        volatile LandSnapshot snapshot;

        @Override
        public CompletionStage<Void> save(LandSnapshot land) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Optional<LandSnapshot>> findById(LandId id) {
            return CompletableFuture.completedFuture(Optional.ofNullable(snapshot));
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findByOwner(OwnerRef owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<LandSnapshot>> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> delete(LandId id) {
            throw new UnsupportedOperationException();
        }
    }

    static final class StubChunks implements ChunkRepository {
        volatile Map<ChunkKey, ChunkFact> facts = Map.of();

        @Override
        public CompletionStage<Void> addChunk(LandId landId, ChunkKey chunk, int storedMinY,
                UUID claimLotId, long costBasisMinor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<List<ChunkKey>> listByLand(LandId landId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Map<ChunkKey, Integer>> listDepthsByLand(LandId landId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Optional<LandId>> findLandByChunk(ChunkKey chunk) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> removeChunk(ChunkKey chunk) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> deleteByLand(LandId landId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Map<ChunkKey, ChunkFact>> factsByLand(LandId landId) {
            return CompletableFuture.completedFuture(facts);
        }
    }

    private record Fixture(UUID world, UUID actor, LandId landId, ChunkKey first, ChunkKey second,
            StubLands lands, StubChunks chunks, DurableRefundValidator validator) {
    }

    private Fixture fixture(boolean serverOwned, long firstBasis, long secondBasis) {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        OwnerRef owner = serverOwned ? OwnerRef.server() : OwnerRef.player(actor);
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey first = new ChunkKey(world, 0, 0);
        ChunkKey second = new ChunkKey(world, 1, 0);
        LandSnapshot snap = new LandSnapshot(landId, "Home", LandName.normalize("Home"), owner,
                world, Set.of(first, second), List.of(), 0, 0, NOW, NOW);
        StubLands lands = new StubLands();
        lands.snapshot = snap;
        StubChunks chunks = new StubChunks();
        chunks.facts = Map.of(
                first, new ChunkRepository.ChunkFact(firstBasis, 64, UUID.randomUUID()),
                second, new ChunkRepository.ChunkFact(secondBasis, 64, UUID.randomUUID()));
        return new Fixture(world, actor, landId, first, second, lands, chunks,
                new DurableRefundValidator(lands, chunks, EMC));
    }

    private static ValidatedRefund validate(DurableRefundValidator validator, Fixture f,
            Set<ChunkKey> keys, long numerator, long denominator) throws Exception {
        RefundRequest request = new RefundRequest(UUID.randomUUID(), f.actor(), f.world(),
                f.landId(), keys, numerator, denominator);
        return validator.validate(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static String rejectionOf(DurableRefundValidator validator, Fixture f,
            Set<ChunkKey> keys, long numerator, long denominator) {
        RefundRequest request = new RefundRequest(UUID.randomUUID(), f.actor(), f.world(),
                f.landId(), keys, numerator, denominator);
        CompletableFuture<ValidatedRefund> stage =
                validator.validate(request).toCompletableFuture();
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> stage.get(5, TimeUnit.SECONDS));
        assertInstanceOf(RefundRejectedException.class, failure.getCause());
        return ((RefundRejectedException) failure.getCause()).diagnosticKey();
    }

    @Test
    void happyPathUsesDurableBasisTimesRatio() throws Exception {
        Fixture f = fixture(false, 101L, 100L);
        ValidatedRefund plan = validate(f.validator(), f, Set.of(f.first(), f.second()), 1, 2);

        assertEquals(201L, plan.totalCostBasisMinorUnits());
        assertEquals(101L, plan.refundAmountMinorUnits());
        assertEquals(2, plan.chunks().size());
        assertEquals(101L, plan.chunks().get(0).costBasisMinorUnits());
        assertEquals(100L, plan.chunks().get(1).costBasisMinorUnits());
    }

    @Test
    void unknownLandIsRejected() {
        Fixture f = fixture(false, 100L, 100L);
        f.lands().snapshot = null;
        assertEquals("refund.unknown_land",
                rejectionOf(f.validator(), f, Set.of(f.first()), 1, 2));
    }

    @Test
    void serverLandIsRejected() {
        Fixture f = fixture(true, 100L, 100L);
        assertEquals("refund.server_land_no_refund",
                rejectionOf(f.validator(), f, Set.of(f.first()), 1, 2));
    }

    @Test
    void staleChunkIsRejected() {
        Fixture f = fixture(false, 100L, 100L);
        ChunkKey foreign = new ChunkKey(f.world(), 9, 9);
        assertEquals("refund.stale", rejectionOf(f.validator(), f, Set.of(foreign), 1, 2));
    }

    @Test
    void missingBasisIsRejected() {
        Fixture f = fixture(false, 100L, 100L);
        f.chunks().facts = Map.of(
                f.first(), new ChunkRepository.ChunkFact(null, 64, UUID.randomUUID()),
                f.second(), new ChunkRepository.ChunkFact(100L, 64, UUID.randomUUID()));
        assertEquals("refund.missing_basis",
                rejectionOf(f.validator(), f, Set.of(f.first()), 1, 2));
    }

    @Test
    void negativeBasisIsRejected() {
        Fixture f = fixture(false, 100L, 100L);
        f.chunks().facts = Map.of(
                f.first(), new ChunkRepository.ChunkFact(-1L, 64, UUID.randomUUID()),
                f.second(), new ChunkRepository.ChunkFact(100L, 64, UUID.randomUUID()));
        assertEquals("refund.invalid_basis",
                rejectionOf(f.validator(), f, Set.of(f.first()), 1, 2));
    }

    @Test
    void basisOverflowIsRejected() {
        Fixture f = fixture(false, 100L, 100L);
        f.chunks().facts = Map.of(
                f.first(), new ChunkRepository.ChunkFact(Long.MAX_VALUE, 64, UUID.randomUUID()),
                f.second(), new ChunkRepository.ChunkFact(Long.MAX_VALUE, 64, UUID.randomUUID()));
        assertEquals("refund.invalid_basis",
                rejectionOf(f.validator(), f, Set.of(f.first(), f.second()), 1, 2));
    }

    @Test
    void legacyRowsResolveDepthAndLotFallbacks() throws Exception {
        Fixture f = fixture(false, 100L, 100L);
        f.chunks().facts = Map.of(
                f.first(), new ChunkRepository.ChunkFact(100L, null, null),
                f.second(), new ChunkRepository.ChunkFact(100L, 64, UUID.randomUUID()));
        ValidatedRefund plan = validate(f.validator(), f, Set.of(f.first(), f.second()), 1, 2);

        assertEquals(200L, plan.totalCostBasisMinorUnits());
        assertEquals(100L, plan.refundAmountMinorUnits());
        assertNotNull(plan.chunks().get(0).claimLotId());
    }
}
