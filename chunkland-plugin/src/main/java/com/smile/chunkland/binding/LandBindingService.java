package com.smile.chunkland.binding;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Command-facing entry for generic Land/SubLand bindings.
 *
 * <p>Every call is scoped to the caller's own owner namespace: the durable
 * owner check runs inside the repository transaction, so a stale caller view
 * can never bind on another owner's land. Each mutation commits its binding
 * row, its audit row, the land revision bump and the owner epoch increment
 * in one persistence transaction and only then rebuilds the immutable
 * snapshot and publishes it once: a failed commit publishes nothing, so
 * readers never observe state that is not durable. Reloads follow the same
 * read-and-publish path without writing anything; a failed load publishes
 * the unloaded marker instead of keeping a previous snapshot.
 */
public final class LandBindingService {

    private final LandBindingRepository repository;
    private final LandAuthorisationCache cache;
    private final Clock clock;

    public LandBindingService(LandBindingRepository repository,
            LandAuthorisationCache cache) {
        this(repository, cache, Clock.systemUTC());
    }

    public LandBindingService(LandBindingRepository repository,
            LandAuthorisationCache cache, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Live runtime snapshot holder fed by every publish here. */
    public LandAuthorisationCache cache() {
        return cache;
    }

    /**
     * Bind one subject on one land, replacing any existing row for the same
     * subject, then publish the rebuilt snapshot.
     */
    public CompletionStage<LandBindingRepository.BindOutcome> bindLand(UUID actor, LandId landId,
            LandBindingRepository.Subject subject, UUID profileId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(profileId, "profileId");
        CompletionStage<LandBindingRepository.BindOutcome> committed;
        try {
            committed = repository.bindLand(actor, landId, subject, profileId, actor,
                    clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("bind repository returned null"));
        }
        return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
    }

    /**
     * Remove one subject's binding on one land, then publish the rebuilt
     * snapshot. Resending without a binding still succeeds.
     */
    public CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(UUID actor,
            LandId landId, LandBindingRepository.Subject subject) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(subject, "subject");
        CompletionStage<LandBindingRepository.UnbindOutcome> committed;
        try {
            committed = repository.unbindLand(actor, landId, subject, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("unbind repository returned null"));
        }
        return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
    }

    /**
     * Bind one subject on one subland, replacing any existing row for the
     * same subject, then publish the rebuilt snapshot.
     */
    public CompletionStage<LandBindingRepository.BindOutcome> bindSubland(UUID actor,
            SubLandId sublandId, LandBindingRepository.Subject subject, UUID profileId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sublandId, "sublandId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(profileId, "profileId");
        CompletionStage<LandBindingRepository.BindOutcome> committed;
        try {
            committed = repository.bindSubland(actor, sublandId, subject, profileId, actor,
                    clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("bind repository returned null"));
        }
        return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
    }

    /**
     * Remove one subject's binding on one subland, then publish the rebuilt
     * snapshot. Resending without a binding still succeeds.
     */
    public CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(UUID actor,
            SubLandId sublandId, LandBindingRepository.Subject subject) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sublandId, "sublandId");
        Objects.requireNonNull(subject, "subject");
        CompletionStage<LandBindingRepository.UnbindOutcome> committed;
        try {
            committed = repository.unbindSubland(actor, sublandId, subject, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("unbind repository returned null"));
        }
        return committed.thenCompose(outcome -> refresh().thenApply(ignored -> outcome));
    }

    /**
     * Re-read the durable generic rows and publish once, without writing
     * anything. The direct layers of the cached snapshot are kept; only the
     * generic layers are replaced. A failed load publishes the unloaded
     * marker instead of keeping a previous snapshot, so a stale view can
     * never authorise after the durable rows moved.
     */
    public CompletionStage<Void> refresh() {
        CompletionStage<LandBindingRepository.GenericSnapshotData> loaded;
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
                    Expanded expanded = expand(data);
                    cache.publish(cache.snapshot().withGeneric(expanded.land, expanded.subland,
                            expanded.members, data.sublandDefaults()));
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        cache.publish(LandAuthorisationSnapshot.unloaded());
                    }
                });
    }

    private record Expanded(
            Map<LandId, List<PermissionBinding>> land,
            Map<SubLandId, List<PermissionBinding>> subland,
            Map<String, Set<UUID>> members) {
    }

    /**
     * Expand durable generic rows into decision bindings plus the membership
     * map that gates group visibility. One corrupt subject or entry is
     * skipped without hiding the rest of the load.
     */
    static Expanded expand(LandBindingRepository.GenericSnapshotData data) {
        Map<LandId, List<PermissionBinding>> land = new HashMap<>();
        Map<SubLandId, List<PermissionBinding>> subland = new HashMap<>();
        Map<String, Set<UUID>> members = new HashMap<>();
        for (Map.Entry<LandId, List<LandBindingRepository.GenericBinding>> entry
                : data.landBindings().entrySet()) {
            List<PermissionBinding> out = expandScope(entry.getValue(), members);
            if (!out.isEmpty()) {
                land.put(entry.getKey(), out);
            }
        }
        for (Map.Entry<SubLandId, List<LandBindingRepository.GenericBinding>> entry
                : data.sublandBindings().entrySet()) {
            List<PermissionBinding> out = expandScope(entry.getValue(), members);
            if (!out.isEmpty()) {
                subland.put(entry.getKey(), out);
            }
        }
        return new Expanded(land, subland, members);
    }

    private static List<PermissionBinding> expandScope(
            List<LandBindingRepository.GenericBinding> bindings, Map<String, Set<UUID>> members) {
        List<PermissionBinding> out = new ArrayList<>();
        for (LandBindingRepository.GenericBinding binding : bindings) {
            if (binding == null) {
                continue;
            }
            PermissionSubject subject;
            try {
                if (LandBindingRepository.SubjectKind.PLAYER.name()
                        .equals(binding.subjectType())) {
                    subject = PermissionSubject.player(binding.subjectId());
                } else if (LandBindingRepository.SubjectKind.GROUP.name()
                        .equals(binding.subjectType())) {
                    // The group subject id is the group UUID rendered as
                    // text: globally unique (display names collide across
                    // owners) and exactly the key the membership map uses,
                    // so the lookup admits members only.
                    subject = PermissionSubject.group(binding.subjectId().toString());
                } else {
                    continue;
                }
            } catch (RuntimeException unexpected) {
                continue;
            }
            if (LandBindingRepository.SubjectKind.GROUP.name().equals(binding.subjectType())) {
                members.computeIfAbsent(binding.subjectId().toString(), ignored -> new HashSet<>())
                        .addAll(binding.members());
            }
            for (Map.Entry<ProtectionActionType, PermissionState> stored
                    : binding.entries().entrySet()) {
                ProtectionActionType action = stored.getKey();
                PermissionState state = stored.getValue();
                if (action == null || state == null || state == PermissionState.INHERIT) {
                    continue;
                }
                if (state != PermissionState.ALLOW && state != PermissionState.DENY) {
                    continue;
                }
                out.add(new PermissionBinding(subject, new Permission(action, state)));
            }
        }
        return List.copyOf(out);
    }
}
