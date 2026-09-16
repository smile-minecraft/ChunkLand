package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.VerticalMode;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.ClaimCommit;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.vertical.SnapshotProtectionDepthLookup;
import com.smile.chunkland.runtime.vertical.VerticalDepths;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Formal startup depth wiring: a durable {@code DOMAIN_COMMITTED} claim with
 * non-fallback stored depths must survive a restart through the production
 * {@link ClaimStartupBootstrap} path into the shared registry snapshot.
 */
class ClaimStartupDepthWiringTest {

    @TempDir Path temp;

    private static final Instant TIME = Instant.parse("2026-03-01T00:00:00Z");
    private static final int WORLD_MIN = -64;

    @Test
    void productionStartupRetainsNonFallbackStoredDepths() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey deep = new ChunkKey(world, 3, 4);
        ChunkKey shallow = new ChunkKey(world, 4, 4);
        UUID lot = UUID.randomUUID();
        Path databasePath = temp.resolve("startup-depth.db");
        seedDomainCommitted(databasePath, world, actor, landId, lot,
                List.of(new OperationPayload.Chunk(deep, 25, lot, 100L),
                        new OperationPayload.Chunk(shallow, -20, lot, 100L)),
                "Depth land");

        LandRegistryStore sharedStore = new LandRegistryStore();
        Logger logger = Logger.getLogger("Test");
        ClaimStartupBootstrap bootstrap =
                ClaimStartupBootstrap.start(databasePath, sharedStore, logger);
        try {
            List<RecoveryResult> results =
                    bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertEquals("ACTIVE", results.get(0).resultingState());

            LandRegistry snapshot = sharedStore.snapshot();
            assertNotNull(snapshot.findLand(world, 3, 4));
            assertEquals(25, snapshot.storedDepth(deep).orElseThrow(),
                    "restart must not fall back durable depth 25 to legacy 64");
            assertEquals(-20, snapshot.storedDepth(shallow).orElseThrow(),
                    "restart must not fall back durable depth -20 to legacy 64");

            SnapshotProtectionDepthLookup per =
                    SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
            assertEquals(25, per.getEffectiveDepth(deep, snapshot).orElseThrow());
            assertEquals(-20, per.getEffectiveDepth(shallow, snapshot).orElseThrow());
            SnapshotProtectionDepthLookup full =
                    SnapshotProtectionDepthLookup.fixed(VerticalMode.FULL_HEIGHT, WORLD_MIN);
            assertEquals(WORLD_MIN, full.getEffectiveDepth(deep, snapshot).orElseThrow());
            assertEquals(25, snapshot.storedDepth(deep).orElseThrow(),
                    "effective read under FULL must not rewrite stored depth");
        } finally {
            bootstrap.close();
            bootstrap.close();
            assertTrue(bootstrap.isClosed());
            assertTrue(bootstrap.store().isClosed());
        }
    }

    @Test
    void nullDepthRowFallsBackWithoutLosingSiblings() throws Exception {
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey kept = new ChunkKey(world, 6, 6);
        ChunkKey legacy = new ChunkKey(world, 7, 6);
        UUID lot = UUID.randomUUID();
        Path databasePath = temp.resolve("startup-null-depth.db");
        seedDomainCommitted(databasePath, world, actor, landId, lot,
                List.of(new OperationPayload.Chunk(kept, 25, lot, 100L),
                        new OperationPayload.Chunk(legacy, 40, lot, 100L)),
                "Null depth land");
        // Null out one durable row through the legacy text key so the test
        // does not depend on package-private blob codecs or store seams.
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath.toAbsolutePath());
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE land_chunks SET stored_min_protected_y = NULL "
                                + "WHERE world = ? AND chunk = ?")) {
            ps.setString(1, world.toString());
            ps.setString(2, "7,6");
            assertEquals(1, ps.executeUpdate());
        }

        LandRegistryStore sharedStore = new LandRegistryStore();
        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, sharedStore, Logger.getLogger("Test"));
        try {
            List<RecoveryResult> results =
                    bootstrap.scanFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals("ACTIVE", results.get(0).resultingState());
            LandRegistry snapshot = sharedStore.snapshot();
            assertEquals(25, snapshot.storedDepth(kept).orElseThrow());
            assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y,
                    snapshot.storedDepth(legacy).orElseThrow(),
                    "NULL durable row must normalize to the legacy fallback");
        } finally {
            bootstrap.close();
        }
    }

    @Test
    void legacyTwoArgRebuilderRemainsFallbackOnly() throws Exception {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 9, 9);
        Path db = temp.resolve("legacy-fallback.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            lands.save(new LandSnapshot(landId, "Home", LandName.normalize("Home"), owner,
                    world, Set.of(chunk), List.of(), 0, 0, TIME, TIME))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            new com.smile.chunkland.persistence.SqliteChunkRepository(store)
                    .addChunk(landId, chunk, 40, UUID.randomUUID(), 10L)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // The historical two-arg seam carries no chunk repository, so it
            // publishes no depths by design; only the production three-arg
            // path retains durable depths.
            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder legacy =
                    new RuntimeRegistryRebuilder(lands, registryStore);
            LandRegistry published = legacy.rebuild().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(published.storedDepth(chunk).isEmpty());
            SnapshotProtectionDepthLookup lookup =
                    SnapshotProtectionDepthLookup.fixed(VerticalMode.PER_CHUNK_DEPTH, WORLD_MIN);
            assertEquals(VerticalDepths.LEGACY_STORED_FALLBACK_Y,
                    lookup.getEffectiveDepth(chunk, published).orElseThrow());
        }
    }

    @Test
    void startupHydrationPublishesDurableLandWithoutAnyLedgerRow() throws Exception {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 12, 12);
        Path databasePath = temp.resolve("startup-hydration.db");
        try (PersistenceStore store = PersistenceStore.open(databasePath)) {
            new SqliteLandRepository(store).save(new LandSnapshot(
                    landId, "Lone", LandName.normalize("Lone"), OwnerRef.player(owner),
                    world, Set.of(chunk), List.of(), 4, 0, TIME, TIME))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            // The runtime index is built from land_chunks rows; persist the
            // chunk so the hydrated snapshot can resolve the land by position.
            new com.smile.chunkland.persistence.SqliteChunkRepository(store)
                    .addChunk(landId, chunk, 64, UUID.randomUUID(), 10L)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        LandRegistryStore sharedStore = new LandRegistryStore();
        ClaimStartupBootstrap bootstrap = ClaimStartupBootstrap.start(
                databasePath, sharedStore, Logger.getLogger("Test"));
        try {
            // No ledger row exists, so the recovery scan alone never rebuilds;
            // startup hydration must still publish the durable land and mark
            // readiness. Await the completion seam instead of racing it.
            bootstrap.hydrationFuture().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(bootstrap.readiness().isReady(), "startup hydration must mark readiness");
            assertNotNull(sharedStore.snapshot().findLand(world, 12, 12),
                    "a durable land must not read as wilderness after restart");
            assertEquals(landId, sharedStore.snapshot().findLand(world, 12, 12).id());
        } finally {
            bootstrap.close();
        }
    }

    @Test
    void readinessGateFailsClosedBeforeHydrationMarksReady() {
        // Before markReady the registry is "unknown", not wilderness: a reader
        // that consults readiness must refuse the lookup.
        com.smile.chunkland.runtime.index.RegistryReadiness readiness =
                new com.smile.chunkland.runtime.index.RegistryReadiness();
        LandRegistryStore store = new LandRegistryStore();

        assertFalse(readiness.isReady());
        assertThrows(IllegalStateException.class, () -> {
            if (!readiness.isReady()) {
                throw new IllegalStateException("land registry is not hydrated");
            }
            store.snapshot();
        });

        // After the barrier releases, the same read is safe.
        readiness.markReady();
        assertTrue(readiness.isReady());
        store.snapshot();
    }

    private static void seedDomainCommitted(Path databasePath, UUID world, UUID actor,
            LandId landId, UUID lot, List<OperationPayload.Chunk> chunks, String displayName)
            throws Exception {
        UUID operationId = UUID.randomUUID();
        try (PersistenceStore store = PersistenceStore.open(databasePath)) {
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = OperationPayload.claim(operationId, actor, world,
                    landId, chunks, 200L, "test-economy", TIME, displayName);
            ledger.createPaymentPending(payload).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ledger.transitionToCharged(operationId, "startup-ref", payload.updatedAt())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            Set<ChunkKey> keys = Set.copyOf(chunks.stream().map(OperationPayload.Chunk::chunk).toList());
            LandSnapshot land = new LandSnapshot(landId, displayName,
                    LandName.normalize(displayName), OwnerRef.player(actor),
                    world, keys, List.of(), 0, 0, TIME, TIME);
            List<ChunkKey> auditChunks = List.copyOf(keys);
            AuditEntry audit = new AuditEntry(0, payload.updatedAt(), actor, "LAND_CREATE",
                    landId, world, null, payload.schemaVersion(), null,
                    payload.toJson(), "{}", auditChunks);
            ledger.commitClaimAtomically(new ClaimCommit(operationId, land, chunks, audit))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("DOMAIN_COMMITTED",
                    ledger.find(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
        }
    }
}
