package com.smile.chunkland.rename;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.LandRenameRepository;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Command-facing entry for land renames.
 *
 * <p>Each rename prechecks the actor against the published snapshot — a
 * player Land needs its owner, a Server Land needs the steward grant, and
 * the grant is ignored on player Land — then commits through
 * {@link LandRenameRepository}, which re-reads the durable owner and names
 * inside its transaction as the final authority. Only after the commit
 * succeeds is a fresh runtime snapshot rebuilt from the authoritative
 * database and published once: a failed commit publishes nothing, so
 * readers never observe a rename that is not durable, and a failed publish
 * degrades without rolling back the durable rename. There is no bypass
 * input anywhere on this path.
 *
 * <p>No cost is computed, no Economy call runs, no chunk is loaded, and the
 * structure revision and selection sessions are never touched: a rename
 * only swaps the display name and key and leaves the authorisation
 * generation untouched, so previously pinned writes keep committing.
 */
public final class LandRenameService {

    private final LandRenameRepository repository;
    private final Supplier<LandRegistry> snapshots;
    private final RuntimeRegistryRebuilder rebuilder;
    private final Clock clock;

    public LandRenameService(LandRenameRepository repository,
            Supplier<LandRegistry> snapshots, RuntimeRegistryRebuilder rebuilder) {
        this(repository, snapshots, rebuilder, Clock.systemUTC());
    }

    public LandRenameService(LandRenameRepository repository,
            Supplier<LandRegistry> snapshots, RuntimeRegistryRebuilder rebuilder, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Rename one land, then publish the rebuilt runtime snapshot.
     *
     * @param actor renaming player
     * @param landId target land
     * @param rawName new display name; blank or control-bearing input rejects
     *                before any write
     * @param serverLandSteward whether the actor holds the server-land grant;
     *                          required on Server Land, ignored on player Land
     * @return the terminal rename outcome, never null
     */
    public CompletionStage<LandRenameResult> rename(UUID actor, LandId landId,
            String rawName, boolean serverLandSteward) {
        if (actor == null || landId == null) {
            return CompletableFuture.completedFuture(LandRenameResult.failed("rename.failed"));
        }
        try {
            LandName.of(rawName == null ? "" : rawName);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(LandRenameResult.rejected("rename.invalid"));
        }
        final LandSnapshot current;
        try {
            LandRegistry snapshot = snapshots.get();
            current = snapshot == null ? null : snapshot.land(landId);
        } catch (RuntimeException unresolved) {
            return CompletableFuture.completedFuture(
                    LandRenameResult.rejected("rename.unknown_land"));
        }
        if (current == null) {
            return CompletableFuture.completedFuture(
                    LandRenameResult.rejected("rename.unknown_land"));
        }
        if (!isAuthorised(actor, current, serverLandSteward)) {
            return CompletableFuture.completedFuture(
                    LandRenameResult.rejected("rename.not_allowed"));
        }
        CompletionStage<LandRenameRepository.Outcome> committed;
        try {
            committed = repository.rename(landId, actor, serverLandSteward,
                    rawName, clock.instant());
        } catch (RenameRejectedException rejected) {
            return CompletableFuture.completedFuture(
                    LandRenameResult.rejected(rejected.diagnosticKey()));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(LandRenameResult.failed("rename.failed"));
        }
        if (committed == null) {
            return CompletableFuture.completedFuture(LandRenameResult.failed("rename.failed"));
        }
        return committed.thenCompose(outcome -> {
            if (outcome == null) {
                return CompletableFuture.completedFuture(
                        LandRenameResult.failed("rename.failed"));
            }
            return publish(outcome);
        }).exceptionally(LandRenameService::mapFailure);
    }

    /**
     * Whether the actor may rename the snapshotted land: the player owner on
     * player Land, the steward grant on Server Land. The grant never
     * authorises player Land and no other state is consulted.
     */
    private static boolean isAuthorised(UUID actor, LandSnapshot land, boolean serverLandSteward) {
        if (land.ownerRef() instanceof OwnerRef.ServerOwnerRef) {
            return serverLandSteward;
        }
        if (land.ownerRef() instanceof OwnerRef.PlayerOwnerRef player) {
            return player.uuid().equals(actor);
        }
        return false;
    }

    private CompletionStage<LandRenameResult> publish(LandRenameRepository.Outcome outcome) {
        CompletionStage<LandRegistry> rebuilt;
        try {
            rebuilt = rebuilder.rebuild();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(degraded(outcome));
        }
        if (rebuilt == null) {
            return CompletableFuture.completedFuture(degraded(outcome));
        }
        return rebuilt.thenApply(ignored -> LandRenameResult.success(
                        outcome.landId(), outcome.oldDisplayName(), outcome.newDisplayName()))
                .exceptionally(failure -> degraded(outcome));
    }

    private static LandRenameResult degraded(LandRenameRepository.Outcome outcome) {
        return LandRenameResult.degraded(outcome.landId(), outcome.oldDisplayName(),
                outcome.newDisplayName(), "rename.publish_failed");
    }

    private static LandRenameResult mapFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RenameRejectedException rejected) {
                return LandRenameResult.rejected(rejected.diagnosticKey());
            }
            current = current.getCause();
        }
        return LandRenameResult.failed("rename.failed");
    }
}
