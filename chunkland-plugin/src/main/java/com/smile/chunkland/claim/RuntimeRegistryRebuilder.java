package com.smile.chunkland.claim;

import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.persistence.LandRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Rebuilds the immutable runtime index from the authoritative database and
 * publishes it with a single volatile write.
 *
 * <p>The saga uses this for step five; startup recovery reuses the same seam
 * for {@code DOMAIN_COMMITTED} rows, so the runtime can never observe a partial
 * state on either path. Callers must invoke it off the persistence thread; the
 * repository read is asynchronous and the snapshot build never touches SQL,
 * Bukkit, or AceLib.
 */
public final class RuntimeRegistryRebuilder {

    private final LandRepository lands;
    private final LandRegistryStore store;

    public RuntimeRegistryRebuilder(LandRepository lands, LandRegistryStore store) {
        this.lands = Objects.requireNonNull(lands, "lands");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Rebuild from all durable lands and publish. */
    public CompletionStage<LandRegistry> rebuild() {
        CompletionStage<List<LandSnapshot>> read;
        try {
            read = lands.findAll();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (read == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("land repository returned null"));
        }
        return read.thenApply(snapshots -> {
            LandRegistry next = LandRegistry.from(snapshots);
            store.publish(next);
            return next;
        });
    }

    /**
     * Recovery seam: rebuild the runtime for a durable domain commit.
     * The entry and payload are accepted for signature compatibility with the
     * recovery handlers; the rebuild always reads authoritative state.
     */
    public CompletionStage<Void> rebuildForRecovery(LedgerEntry entry, OperationPayload payload) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(payload, "payload");
        return rebuild().thenApply(ignored -> null);
    }
}
