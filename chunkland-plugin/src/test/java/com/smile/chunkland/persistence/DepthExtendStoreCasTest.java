package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.runtime.vertical.DepthExtendRequest;
import com.smile.chunkland.runtime.vertical.DepthWriteResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQL conditional-update (CAS) guard for depth extends.
 *
 * <p>The stored row must move only through one conditional {@code UPDATE}
 * that already enforces the owning land and the strictly-deeper rule, and
 * only an affected row may record a {@code DEPTH_EXTEND} audit. Duplicate
 * concurrent proposals for the same depth converge to exactly one audit.
 */
class DepthExtendStoreCasTest {

    @TempDir Path temp;

    private static LandSnapshot land(UUID world, LandId id, OwnerRef owner, Set<ChunkKey> chunks) {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        return new LandSnapshot(id, "Home", LandName.normalize("Home"), owner, world,
                chunks, List.of(), 0, 0, now, now);
    }

    @Test
    void storeUsesConditionalUpdateWithLandAndDepthGuard() throws Exception {
        Path source = Path.of("chunkland-plugin/src/main/java/com/smile/chunkland/persistence/DepthExtendStore.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/smile/chunkland/persistence/DepthExtendStore.java");
        }
        String sql = Files.readString(source);
        assertTrue(sql.contains("COALESCE(stored_min_protected_y"),
                "extend UPDATE must compare against the nullable durable value with COALESCE");
        assertTrue(sql.contains("land_id = ?") && sql.contains("UPDATE land_chunks SET stored_min_protected_y"),
                "extend UPDATE must enforce the owning land in the same statement");
        assertTrue(sql.contains("executeUpdate"),
                "extend must branch on the conditional UPDATE affected-row count");
    }

    @Test
    void concurrentDuplicateProposalsYieldExactlyOneAudit() throws Exception {
        UUID world = UUID.randomUUID();
        LandId landId = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        ChunkKey chunk = new ChunkKey(world, 7, 3);
        UUID actor = UUID.randomUUID();
        Path db = temp.resolve("cas-dup.db");
        try (PersistenceStore store = PersistenceStore.open(db)) {
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            lands.save(land(world, landId, owner, Set.of(chunk))).toCompletableFuture().join();
            chunks.addChunk(landId, chunk, 60, UUID.randomUUID(), 10L).toCompletableFuture().join();

            DepthExtendStore extends_ = new DepthExtendStore(store);
            int writers = 2;
            CyclicBarrier start = new CyclicBarrier(writers);
            ExecutorService exec = Executors.newFixedThreadPool(writers);
            try {
                List<CompletableFuture<DepthWriteResult>> pending = new java.util.ArrayList<>();
                for (int i = 0; i < writers; i++) {
                    pending.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await(5, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                        DepthExtendRequest request =
                                new DepthExtendRequest(chunk, landId, actor, 10, 15);
                        return extends_.extend(request).toCompletableFuture().join();
                    }, exec));
                }
                List<DepthWriteResult> results = pending.stream()
                        .map(future -> {
                            try {
                                return future.get(10, TimeUnit.SECONDS);
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        })
                        .toList();
                long applied = results.stream().filter(DepthWriteResult::applied).count();
                assertEquals(1, applied, "exactly one duplicate writer must win the conditional update");
            } finally {
                exec.shutdownNow();
                assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
            }

            assertEquals(10, chunks.listDepthsByLand(landId).toCompletableFuture().join().get(chunk));
            assertEquals(1, new SqliteAuditRepository(store).findByAction("DEPTH_EXTEND", 10)
                    .toCompletableFuture().join().size(),
                    "only the conditional-UPDATE winner may record an audit");
        }
    }
}
