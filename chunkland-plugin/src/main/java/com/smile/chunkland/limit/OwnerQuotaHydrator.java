package com.smile.chunkland.limit;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.persistence.LandRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Rebuilds the in-memory owner quota counters from the authoritative durable
 * domain after a restart.
 *
 * <p>The quota service is process-local: every restart creates a fresh one
 * starting from zero. The durable truth is the {@code lands} / {@code
 * land_chunks} tables read through the {@link LandRepository} — the same
 * source the runtime rebuilder publishes — so hydration counts committed
 * lands and their chunk sets per owner and installs them as the committed
 * baseline. Reserved counts always restart at zero because reservations never
 * survive a restart. Server-owned lands are skipped: they bypass quota.
 *
 * <p>No Economy is involved: hydration is a pure durable-count restore inside
 * the persistence boundary.
 */
public final class OwnerQuotaHydrator {

    /** How long the blocking startup hydration waits for the durable read. */
    public static final Duration HYDRATION_TIMEOUT = Duration.ofSeconds(15);

    private OwnerQuotaHydrator() {
    }

    /**
     * Load every durable land and install per-owner committed counts.
     *
     * @return a stage completing when all counters are installed
     */
    public static CompletionStage<Void> hydrate(OwnerQuotaService quotas, LandRepository lands) {
        Objects.requireNonNull(quotas, "quotas");
        Objects.requireNonNull(lands, "lands");
        CompletionStage<List<LandSnapshot>> read;
        try {
            read = lands.findAll();
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        if (read == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("land repository returned null"));
        }
        return read.thenApply(snapshots -> {
            Map<String, Accumulator> byOwner = new HashMap<>();
            Map<String, OwnerRef> refs = new HashMap<>();
            for (LandSnapshot snapshot : snapshots) {
                OwnerRef owner = snapshot.ownerRef();
                if (owner instanceof OwnerRef.ServerOwnerRef) {
                    continue;
                }
                byOwner.computeIfAbsent(owner.key(), ignored -> new Accumulator()).add(snapshot.chunks().size());
                refs.putIfAbsent(owner.key(), owner);
            }
            for (Map.Entry<String, Accumulator> entry : byOwner.entrySet()) {
                OwnerRef owner = refs.get(entry.getKey());
                quotas.setLandCommitted(owner, entry.getValue().lands);
                quotas.setChunkCommitted(owner, entry.getValue().chunks);
            }
            return null;
        });
    }

    /**
     * Blocking startup helper: hydrate and wait up to {@link #HYDRATION_TIMEOUT}.
     * Must run outside the persistence thread; on timeout or failure the caller
     * must keep claiming unavailable rather than serve claims from zero.
     */
    public static void hydrateBlocking(OwnerQuotaService quotas, LandRepository lands) {
        Objects.requireNonNull(quotas, "quotas");
        Objects.requireNonNull(lands, "lands");
        try {
            hydrate(quotas, lands).toCompletableFuture().get(HYDRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("quota hydration interrupted", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("quota hydration timed out", timeout);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("quota hydration failed", cause);
        }
    }

    private static final class Accumulator {
        int lands;
        int chunks;

        void add(int chunkCount) {
            lands = Math.addExact(lands, 1);
            chunks = Math.addExact(chunks, chunkCount);
        }
    }
}
