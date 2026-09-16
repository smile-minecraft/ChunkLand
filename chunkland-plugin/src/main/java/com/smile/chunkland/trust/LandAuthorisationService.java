package com.smile.chunkland.trust;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind;
import com.smile.chunkland.api.event.PermissionChangedEvent.SubjectKind;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.PermissionChangedBukkitEvent;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.protection.DirectTrustWhitelist;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Command-facing entry for direct trust, land defaults, and ENTRY bans.
 *
 * <p>Each mutation commits its durable rows and its audit row in one
 * persistence transaction through {@link LandAuthorisationRepository} and
 * only then rebuilds the immutable {@link LandAuthorisationSnapshot} and
 * publishes it once: a failed commit publishes nothing, so readers never
 * observe state that is not durable. The snapshot rebuild re-reads the
 * authoritative rows, so the published value always reflects the database
 * rather than the request. Audit before values are read from the durable
 * rows inside that same transaction, never from the volatile cache, so a
 * stale snapshot cannot misrecord consecutive mutations. Reloads (startup
 * and explicit refresh) follow the same read-and-publish path without
 * writing anything.
 */
public final class LandAuthorisationService {

    private final LandAuthorisationRepository repository;
    private final LandAuthorisationCache cache;
    private final Clock clock;
    private final PublicEvents events;

    public LandAuthorisationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache) {
        this(repository, cache, Clock.systemUTC());
    }

    public LandAuthorisationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache, Clock clock) {
        this(repository, cache, clock, PublicEvents.noop());
    }

    /**
     * @param events public Post dispatch for committed permission changes;
     *         {@code null} means no public events (mutations still commit
     *         exactly as before)
     */
    public LandAuthorisationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache, Clock clock, PublicEvents events) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = events == null ? PublicEvents.noop() : events;
    }

    /** Live runtime snapshot holder fed by every publish here. */
    public LandAuthorisationCache cache() {
        return cache;
    }

    /**
     * Trust one player on one land, then publish the rebuilt snapshot.
     * Idempotent: resending reuses the same implicit profile and binding.
     */
    public CompletionStage<Void> trust(UUID actor, LandId landId, UUID target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        CompletionStage<UUID> committed;
        try {
            committed = repository.trust(landId, target, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("trust repository returned null"));
        }
        return committed.thenCompose(ignored -> refresh()).thenApply(ignored -> {
            // Public Post: exactly once, after the durable commit plus the
            // snapshot publish. Listener failures are isolated and never
            // roll back the committed trust.
            firePermissionChanged(actor, landId, ChangeKind.TRUST, SubjectKind.PLAYER, target,
                    null, null, null);
            return null;
        });
    }

    /**
     * Remove one player's binding on one land, then publish the rebuilt
     * snapshot. The implicit profile is kept so other lands keep working.
     * Resending without a binding still succeeds.
     */
    public CompletionStage<Void> untrust(UUID actor, LandId landId, UUID target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        CompletionStage<Void> committed;
        try {
            committed = repository.untrust(landId, target, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("untrust repository returned null"));
        }
        return committed.thenCompose(ignored -> refresh()).thenApply(ignored -> {
            firePermissionChanged(actor, landId, ChangeKind.UNTRUST, SubjectKind.PLAYER, target,
                    null, null, null);
            return null;
        });
    }

    /**
     * Persist one land default ({@code INHERIT} deletes the row), then
     * publish the rebuilt snapshot. Only whitelisted subject actions are
     * accepted; anything else fails before any write.
     */
    public CompletionStage<Void> setDefault(UUID actor, LandId landId,
            ProtectionActionType action, PermissionState state) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
        DirectTrustWhitelist.requireAllowed(action);
        CompletionStage<Void> committed;
        try {
            committed = repository.setDefault(landId, action, state, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("default repository returned null"));
        }
        return committed.thenCompose(ignored -> refresh()).thenApply(ignored -> {
            firePermissionChanged(actor, landId, ChangeKind.DEFAULT_SET, null, null,
                    action, state, null);
            return null;
        });
    }

    /**
     * Ban one player from one land, then publish the rebuilt snapshot.
     * Idempotent: resending while already banned still succeeds. The land
     * owner and Server Land fail closed before any write.
     */
    public CompletionStage<Void> ban(UUID actor, LandId landId, UUID target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        CompletionStage<Void> committed;
        try {
            committed = repository.ban(landId, target, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ban repository returned null"));
        }
        return committed.thenCompose(ignored -> refresh()).thenApply(ignored -> {
            firePermissionChanged(actor, landId, ChangeKind.BAN, SubjectKind.PLAYER, target,
                    null, null, null);
            return null;
        });
    }

    /**
     * Remove one player's ENTRY ban on one land, then publish the rebuilt
     * snapshot. Trust bindings and land defaults are kept. Resending
     * without a ban still succeeds.
     */
    public CompletionStage<Void> unban(UUID actor, LandId landId, UUID target) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(target, "target");
        CompletionStage<Void> committed;
        try {
            committed = repository.unban(landId, target, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("unban repository returned null"));
        }
        return committed.thenCompose(ignored -> refresh()).thenApply(ignored -> {
            firePermissionChanged(actor, landId, ChangeKind.UNBAN, SubjectKind.PLAYER, target,
                    null, null, null);
            return null;
        });
    }

    /**
     * Fire the public permission Post after commit plus publish. Never
     * throws: construction and listener failures are isolated by the
     * facade, so the committed mutation still stands.
     */
    private void firePermissionChanged(UUID actor, LandId landId, ChangeKind kind,
            SubjectKind subjectKind, UUID subjectId, ProtectionActionType action,
            PermissionState state, UUID profileId) {
        try {
            PermissionChangedEvent event = new PermissionChangedEvent(actor, landId, null,
                    kind, subjectKind, subjectId, action, state, profileId);
            events.firePost(event, () -> new PermissionChangedBukkitEvent(event.actorUuid(),
                    event.landId(), event.subLandId(), event.kind(), event.subjectKind(),
                    event.subjectId(), event.action(), event.state(), event.profileId(), true));
        } catch (Throwable ignored) {
            // Post dispatch must never break the committed permission path.
        }
    }

    /**
     * Re-read the durable rows and publish once, without writing anything.
     * Used at startup and for explicit refreshes; a failed load publishes
     * the unloaded marker instead of keeping a previous snapshot, so a
     * stale unbanned view can never admit a freshly committed ban.
     */
    public CompletionStage<Void> refresh() {
        CompletionStage<LandAuthorisationRepository.SnapshotData> loaded;
        try {
            loaded = repository.loadSnapshotData();
        } catch (RuntimeException failure) {
            cache.publish(LandAuthorisationSnapshot.unloaded());
            return CompletableFuture.failedFuture(failure);
        }
        if (loaded == null) {
            cache.publish(LandAuthorisationSnapshot.unloaded());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("snapshot load returned null"));
        }
        return loaded.thenAccept(data -> {
                    if (data == null) {
                        cache.publish(LandAuthorisationSnapshot.unloaded());
                        throw new IllegalStateException("snapshot load returned null data");
                    }
                    // The direct load owns only the direct layers: merge them
                    // over the cached generic bindings instead of wiping rows
                    // another refresh path published.
                    cache.publish(cache.snapshot().withDirect(
                            data.directAllows(), data.landDefaults(), data.entryBans()));
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        cache.publish(LandAuthorisationSnapshot.unloaded());
                    }
                });
    }
}
