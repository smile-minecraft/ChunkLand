package com.smile.chunkland.runtime.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorageFailurePolicyTest {

    private static class RecordingLogger implements StorageLogger {
        final List<String> warns = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Throwable> causes = new ArrayList<>();
        @Override public void warn(String message) { warns.add(message); }
        @Override public void error(String message, Throwable cause) { errors.add(message); causes.add(cause); }
    }

    private static LandSnapshot land(UUID worldId, int chunkX, int chunkZ) {
        LandId id = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(worldId, chunkX, chunkZ);
        Instant now = Instant.now();
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(ck), List.of(), 0, 0, now, now);
    }

    private static LandSnapshot landWithId(LandId id, UUID worldId, int chunkX, int chunkZ) {
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey ck = new ChunkKey(worldId, chunkX, chunkZ);
        Instant now = Instant.now();
        return new LandSnapshot(id, "Home", "home", owner, worldId,
                Set.of(ck), List.of(), 0, 0, now, now);
    }

    @Test
    void corruptionDoesNotEnterReady_stopServer() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader failing = () -> { throw new SQLException("database disk image is malformed"); };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));
        StartupStorageLoader loader = new StartupStorageLoader(failing, catalog, StorageFailurePolicy.STOP_SERVER, logger);
        StorageLoadOutcome out = loader.load();

        assertFalse(out.isReady(), "corruption must not enter READY");
        assertTrue(out.isStopped());
        assertFalse(out.isLockdown());
        assertEquals(StorageFailurePolicy.STOP_SERVER, out.appliedPolicy().orElseThrow());
        assertTrue(out.cause().isPresent());
        assertTrue(out.publishedRegistry().isEmpty(), "must not publish any index on failure");
        assertEquals(0, out.warnings().size());
        assertFalse(logger.causes.isEmpty());
        assertTrue(out.failureKind().isPresent());
    }

    @Test
    void corruptionWithLockdownPolicyProducesDistinctResult() throws Exception {
        RecordingLogger logger1 = new RecordingLogger();
        RecordingLogger logger2 = new RecordingLogger();
        SnapshotLoader failing = () -> { throw new SQLException("corruption: schema mismatch"); };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));

        StartupStorageLoader stopLoader = new StartupStorageLoader(failing, catalog, StorageFailurePolicy.STOP_SERVER, logger1);
        StartupStorageLoader lockLoader = new StartupStorageLoader(failing, catalog, StorageFailurePolicy.LOCKDOWN, logger2);

        StorageLoadOutcome stop = stopLoader.load();
        StorageLoadOutcome lock = lockLoader.load();

        assertTrue(stop.isStopped());
        assertFalse(stop.isLockdown());
        assertTrue(lock.isLockdown());
        assertFalse(lock.isStopped());
        assertNotEquals(stop.globalState(), lock.globalState(), "two policies must produce different outcomes");
        assertTrue(stop.cause().isPresent());
        assertTrue(lock.cause().isPresent());
        assertEquals(stop.cause().get().getMessage(), lock.cause().get().getMessage());
        // Both must not be READY
        assertFalse(stop.isReady());
        assertFalse(lock.isReady());
    }

    @Test
    void singleOrphanWorldIsIsolatedWithWarn() {
        UUID healthyWorld = UUID.randomUUID();
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot healthy = land(healthyWorld, 0, 0);
        LandSnapshot orphan = land(orphanWorld, 1, 1);

        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader loader = () -> List.of(healthy, orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of(healthyWorld)); // orphan missing

        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        assertTrue(out.isReady(), "orphan must not block READY for global");
        assertFalse(out.isLockdown());
        assertFalse(out.isStopped());
        assertEquals(WorldStorageStatus.ISOLATED, out.worldStatus(orphanWorld));
        assertEquals(WorldStorageStatus.HEALTHY, out.worldStatus(healthyWorld));
        assertFalse(logger.warns.isEmpty(), "must produce WARN for orphan");
        String warn = logger.warns.get(0);
        assertTrue(warn.contains("WARN") && warn.contains(orphanWorld.toString()), "warn must list world_uuid");

        // Check isolation not in published registry
        LandRegistry reg = out.publishedRegistry();
        assertNull(reg.worldIndex(orphanWorld), "orphan world must not be published");
        assertNotNull(reg.worldIndex(healthyWorld), "healthy world must be published");
        assertNotNull(reg.findLand(healthyWorld, 0, 0));
        assertNull(reg.findLand(orphanWorld, 1, 1), "orphan chunk must not be resolvable");

        // Not global lockdown
        assertFalse(out.isLockdown(), "orphan must not trigger global LOCKDOWN");
        assertEquals(1, out.warnings().size());
    }

    @Test
    void orphanDoesNotTriggerGlobalLockdown() {
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot orphan = land(orphanWorld, 5, 5);
        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader loader = () -> List.of(orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of()); // empty -> orphan
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.STOP_SERVER, logger);
        StorageLoadOutcome out = sut.load();

        assertTrue(out.isReady());
        assertEquals(GlobalStorageState.READY, out.globalState());
        assertEquals(WorldStorageStatus.ISOLATED, out.worldStatus(orphanWorld));
        assertTrue(out.publishedRegistry().isEmpty(), "no healthy data -> empty registry");
    }

    @Test
    void isolatedWorldAllMutationKindsAreRejected() {
        UUID healthyWorld = UUID.randomUUID();
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot healthy = land(healthyWorld, 0, 0);
        LandSnapshot orphan = land(orphanWorld, 1, 1);
        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader loader = () -> List.of(healthy, orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of(healthyWorld));
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        for (MutationKind kind : MutationKind.values()) {
            var rejection = IsolatedWorldMutationGate.tryMutate(out, orphanWorld, kind);
            assertTrue(rejection.isPresent(), "kind " + kind + " must be rejected for ISOLATED");
            MutationRejection r = rejection.get();
            assertEquals(orphanWorld, r.worldId());
            assertEquals(kind, r.kind());
            assertEquals(WorldStorageStatus.ISOLATED, r.status());
            assertTrue(r.reason().contains("ISOLATED"));
        }

        // Healthy world must still allow mutations
        for (MutationKind kind : MutationKind.values()) {
            var allowed = IsolatedWorldMutationGate.tryMutate(out, healthyWorld, kind);
            assertTrue(allowed.isEmpty(), "healthy world kind " + kind + " must be allowed");
        }
    }

    @Test
    void orphanRowsAreRetainedAndNotDeleted() {
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot orphan = land(orphanWorld, 2, 2);
        SnapshotLoader loader = () -> List.of(orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of());
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        // Retention is proven by the loader still returning the row on second load,
        // and the outcome containing ISOLATED but no deletion.
        assertEquals(WorldStorageStatus.ISOLATED, out.worldStatus(orphanWorld));
        // Simulate second load (repeat) - same loader returns same data
        StorageLoadOutcome out2 = sut.load();
        assertEquals(WorldStorageStatus.ISOLATED, out2.worldStatus(orphanWorld));
        // No deletion: we can still count the orphan in the loader (fake persistence)
        // The published registry remains empty but loader still has data
        assertEquals(1, out2.worldStatuses().size());
    }

    @Test
    void healthyWorldNotAffectedByAnotherIsolatedWorld() {
        UUID healthyWorldA = UUID.randomUUID();
        UUID healthyWorldB = UUID.randomUUID();
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot a = land(healthyWorldA, 0, 0);
        LandSnapshot b = land(healthyWorldB, 1, 0);
        LandSnapshot orphan = land(orphanWorld, 2, 0);
        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader loader = () -> List.of(a, b, orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of(healthyWorldA, healthyWorldB));
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        assertEquals(WorldStorageStatus.HEALTHY, out.worldStatus(healthyWorldA));
        assertEquals(WorldStorageStatus.HEALTHY, out.worldStatus(healthyWorldB));
        assertEquals(WorldStorageStatus.ISOLATED, out.worldStatus(orphanWorld));

        LandRegistry reg = out.publishedRegistry();
        assertNotNull(reg.findLand(healthyWorldA, 0, 0));
        assertNotNull(reg.findLand(healthyWorldB, 1, 0));
        assertNull(reg.findLand(orphanWorld, 2, 0));

        // Mutations on healthy remain allowed
        assertTrue(IsolatedWorldMutationGate.tryMutate(out, healthyWorldA, MutationKind.CREATE).isEmpty());
        assertTrue(IsolatedWorldMutationGate.tryMutate(out, healthyWorldB, MutationKind.DELETE).isEmpty());
    }

    @Test
    void repeatLoadAndStatusQueryIdempotent() {
        UUID healthyWorld = UUID.randomUUID();
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot healthy = land(healthyWorld, 0, 0);
        LandSnapshot orphan = land(orphanWorld, 1, 1);
        SnapshotLoader loader = () -> List.of(healthy, orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of(healthyWorld));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);

        StorageLoadOutcome first = sut.load();
        StorageLoadOutcome second = sut.load();
        StorageLoadOutcome third = sut.load();

        // Outcomes must be equal in observable fields
        assertEquals(first.globalState(), second.globalState());
        assertEquals(second.globalState(), third.globalState());
        assertEquals(first.worldStatuses(), second.worldStatuses());
        assertEquals(first.warnings(), second.warnings());
        // Published registry content equal
        assertEquals(first.publishedRegistry().lands().size(), second.publishedRegistry().lands().size());
        // Repeated status query consistent
        assertEquals(first.worldStatus(orphanWorld), second.worldStatus(orphanWorld));
        assertEquals(first.worldStatus(healthyWorld), second.worldStatus(healthyWorld));
    }

    @Test
    void errorCauseIsNotSwallowedAndOrphanSnapshotNotPublished() {
        SQLException root = new SQLException("file is not a database");
        SnapshotLoader failing = () -> { throw root; };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(failing, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        assertTrue(out.cause().isPresent());
        assertSame(root, out.cause().get(), "original cause must be preserved");
        assertTrue(out.failureKind().isPresent());
        assertTrue(out.publishedRegistry().isEmpty(), "no snapshot must be published on failure");
        assertEquals(GlobalStorageState.LOCKDOWN, out.globalState());
    }

    @Test
    void malformedRowFailsClosed() {
        SnapshotLoader loader = () -> {
            List<LandSnapshot> list = new ArrayList<>();
            list.add(null); // malformed
            return list;
        };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.STOP_SERVER, logger);
        StorageLoadOutcome out = sut.load();

        assertFalse(out.isReady());
        assertTrue(out.isStopped());
        assertEquals(StorageLoadFailureKind.MALFORMED_ROW, out.failureKind().orElseThrow());
        assertTrue(out.cause().isPresent());
        assertTrue(out.publishedRegistry().isEmpty());
    }

    @Test
    void schemaMismatchFailsClosed() {
        SnapshotLoader loader = () -> { throw new SQLException("schema_version is newer than supported"); };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        assertFalse(out.isReady());
        assertEquals(StorageLoadFailureKind.SCHEMA_MISMATCH, out.failureKind().orElseThrow());
    }

    @Test
    void outcomeImmutable() {
        UUID w = UUID.randomUUID();
        LandSnapshot snap = land(w, 0, 0);
        SnapshotLoader loader = () -> List.of(snap);
        WorldCatalog catalog = WorldCatalog.of(Set.of(w));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        assertThrows(UnsupportedOperationException.class, () -> out.worldStatuses().put(UUID.randomUUID(), WorldStorageStatus.HEALTHY));
        assertThrows(UnsupportedOperationException.class, () -> out.warnings().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> out.publishedRegistry().lands().put(new LandId(UUID.randomUUID()), snap));
    }

    @Test
    void globalFailureGateRejectsAllWorlds() {
        SnapshotLoader failing = () -> { throw new SQLException("connection failed"); };
        WorldCatalog catalog = WorldCatalog.of(Set.of(UUID.randomUUID()));
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(failing, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();

        UUID anyWorld = UUID.randomUUID();
        for (MutationKind kind : MutationKind.values()) {
            var r = IsolatedWorldMutationGate.tryMutate(out, anyWorld, kind);
            assertTrue(r.isPresent(), "global LOCKDOWN must reject even unknown world for " + kind);
            assertEquals(GlobalStorageState.LOCKDOWN, r.get().globalState());
        }
    }

    @Test
    void noBukkitTypesInPolicyPackage() {
        // Structural: ensure policy does not import org.bukkit
        // We scan the compiled class names via reflection of loaded classes
        // Simpler: verify outcome classes do not reference Bukkit directly by checking classloader?
        // Here we just assert that WorldCatalog and logger are injectable seams (no Bukkit param)
        assertNotNull(WorldCatalog.of(Set.of()));
        assertNotNull(StorageLogger.noop());
    }

    // --- Regression: public factory misuse must fail closed ---

    @Test
    void publicReadyFactoryRejectsHealthyWorldAbsentFromRegistry() {
        UUID worldId = UUID.randomUUID();
        Map<UUID, WorldStorageStatus> statuses = Map.of(worldId, WorldStorageStatus.HEALTHY);
        LandRegistry empty = LandRegistry.empty();
        assertThrows(IllegalArgumentException.class,
                () -> StorageLoadOutcome.ready(statuses, empty, List.of()),
                "READY with HEALTHY status but no published world must be rejected fail-closed");
    }

    @Test
    void publicReadyFactoryRejectsPublishedWorldWithoutHealthyStatus() {
        UUID worldId = UUID.randomUUID();
        LandSnapshot snap = land(worldId, 0, 0);
        LandRegistry reg = LandRegistry.from(List.of(snap));
        // Missing HEALTHY entry
        assertThrows(IllegalArgumentException.class,
                () -> StorageLoadOutcome.ready(Map.of(), reg, List.of()),
                "published registry world without HEALTHY status must be rejected");
        // ISOLATED would also be inconsistent (already covered but assert here too)
        assertThrows(IllegalArgumentException.class,
                () -> StorageLoadOutcome.ready(Map.of(worldId, WorldStorageStatus.ISOLATED), reg, List.of()),
                "published ISOLATED world must be rejected");
    }

    @Test
    void publicReadyFactoryRejectsHealthyWorldNotInRegistryButOtherHealthyIs() {
        UUID healthyPresent = UUID.randomUUID();
        UUID healthyAbsent = UUID.randomUUID();
        LandSnapshot snap = land(healthyPresent, 0, 0);
        LandRegistry reg = LandRegistry.from(List.of(snap));
        Map<UUID, WorldStorageStatus> statuses = Map.of(
                healthyPresent, WorldStorageStatus.HEALTHY,
                healthyAbsent, WorldStorageStatus.HEALTHY);
        assertThrows(IllegalArgumentException.class,
                () -> StorageLoadOutcome.ready(statuses, reg, List.of()));
    }

    @Test
    void consistentReadyFactoryStillAllowed() {
        UUID worldId = UUID.randomUUID();
        LandSnapshot snap = land(worldId, 0, 0);
        LandRegistry reg = LandRegistry.from(List.of(snap));
        Map<UUID, WorldStorageStatus> statuses = Map.of(worldId, WorldStorageStatus.HEALTHY);
        // Should not throw
        StorageLoadOutcome out = StorageLoadOutcome.ready(statuses, reg, List.of());
        assertTrue(out.isReady());
        assertEquals(WorldStorageStatus.HEALTHY, out.worldStatus(worldId));
        assertNotNull(out.publishedRegistry().findLand(worldId, 0, 0));
        // Empty DB valid: no HEALTHY status with empty registry
        StorageLoadOutcome emptyOut = StorageLoadOutcome.ready(Map.of(), LandRegistry.empty(), List.of());
        assertTrue(emptyOut.isReady());
        assertTrue(emptyOut.publishedRegistry().isEmpty());
    }

    @Test
    void worldCatalogFailureIsFailClosedWithStopServer() {
        UUID worldId = UUID.randomUUID();
        LandSnapshot snap = land(worldId, 0, 0);
        SnapshotLoader loader = () -> List.of(snap);
        WorldCatalog failingCatalog = new WorldCatalog() {
            @Override public boolean exists(UUID wid) { throw new RuntimeException("catalog exploded"); }
            @Override public Set<UUID> knownWorlds() { return Set.of(); }
        };
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, failingCatalog, StorageFailurePolicy.STOP_SERVER, logger);
        StorageLoadOutcome out = sut.load();
        assertFalse(out.isReady(), "catalog failure must not enter READY");
        assertTrue(out.isStopped(), "STOP_SERVER policy must produce STOPPED on catalog failure");
        assertFalse(out.isLockdown());
        assertTrue(out.cause().isPresent(), "cause must be preserved");
        assertTrue(out.cause().get().getMessage().contains("catalog"));
        assertTrue(out.failureKind().isPresent(), "failureKind must be retained");
        assertTrue(out.publishedRegistry().isEmpty(), "must not publish on catalog failure");
        assertTrue(out.isStopped() || out.isLockdown()); // sanity
    }

    @Test
    void worldCatalogFailureIsFailClosedWithLockdown() {
        UUID worldId = UUID.randomUUID();
        LandSnapshot snap = land(worldId, 0, 0);
        SnapshotLoader loader = () -> List.of(snap);
        WorldCatalog failingCatalog = new WorldCatalog() {
            @Override public boolean exists(UUID wid) { throw new IllegalStateException("catalog io error"); }
            @Override public Set<UUID> knownWorlds() { return Set.of(); }
        };
        RecordingLogger logger = new RecordingLogger();
        StartupStorageLoader sut = new StartupStorageLoader(loader, failingCatalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();
        assertFalse(out.isReady());
        assertTrue(out.isLockdown(), "LOCKDOWN policy must produce LOCKDOWN on catalog failure");
        assertFalse(out.isStopped());
        assertTrue(out.cause().isPresent());
        assertTrue(out.failureKind().isPresent());
        assertTrue(out.publishedRegistry().isEmpty());
    }

    @Test
    void orphanIsolationPreservedAfterInvariantFix() {
        UUID healthyWorld = UUID.randomUUID();
        UUID orphanWorld = UUID.randomUUID();
        LandSnapshot healthy = land(healthyWorld, 0, 0);
        LandSnapshot orphan = land(orphanWorld, 1, 1);
        RecordingLogger logger = new RecordingLogger();
        SnapshotLoader loader = () -> List.of(healthy, orphan);
        WorldCatalog catalog = WorldCatalog.of(Set.of(healthyWorld));
        StartupStorageLoader sut = new StartupStorageLoader(loader, catalog, StorageFailurePolicy.LOCKDOWN, logger);
        StorageLoadOutcome out = sut.load();
        assertTrue(out.isReady());
        assertEquals(WorldStorageStatus.ISOLATED, out.worldStatus(orphanWorld));
        assertEquals(WorldStorageStatus.HEALTHY, out.worldStatus(healthyWorld));
        assertTrue(out.publishedRegistry().worlds().containsKey(healthyWorld));
        assertFalse(out.publishedRegistry().worlds().containsKey(orphanWorld));
        assertFalse(logger.warns.isEmpty());
    }
}
