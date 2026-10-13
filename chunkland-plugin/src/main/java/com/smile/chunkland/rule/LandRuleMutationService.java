package com.smile.chunkland.rule;

import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.RuleChangedBukkitEvent;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Service-facing entry for durable per-land environment rules.
 *
 * <p>Each mutation commits its rule row and its audit row in one
 * persistence transaction through {@link LandAuthorisationRepository} and
 * only then rebuilds the immutable {@link LandAuthorisationSnapshot} and
 * publishes it once: a failed commit publishes nothing, so readers never
 * observe state that is not durable. An {@code INHERIT} write deletes the
 * override row instead of storing a value, so the world, global and
 * built-in rule layers apply again. Reloads (startup and explicit refresh)
 * follow the same read-and-publish path without writing anything.
 *
 * <p>Exactly one {@link RuleChangedEvent} fires per committed mutation,
 * after the durable commit plus the snapshot publish; stale or lapsed
 * confirmations change nothing and fire nothing. The event carries the
 * durable before value ({@code null} when no override existed) and the
 * requested state ({@code INHERIT} when the override was removed).
 */
public final class LandRuleMutationService {

    private final LandAuthorisationRepository repository;
    private final LandAuthorisationCache cache;
    private final Clock clock;
    private final PublicEvents events;
    private final java.util.function.Supplier<
            java.util.concurrent.CompletionStage<
                    LandAuthorisationRepository.SnapshotData>> reloader;

    public LandRuleMutationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache) {
        this(repository, cache, Clock.systemUTC());
    }

    public LandRuleMutationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache, Clock clock) {
        this(repository, cache, clock, PublicEvents.noop());
    }

    /**
     * @param events public Post dispatch for committed rule changes;
     *         {@code null} means no public events (mutations still commit
     *         exactly as before)
     */
    public LandRuleMutationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache, Clock clock, PublicEvents events) {
        this(repository, cache, clock, events, repository::loadSnapshotData);
    }

    /**
     * Reload seam behind {@link #refresh}: production passes the
     * repository load, tests substitute a failing load to prove a committed
     * write with a failed publish surfaces distinctly.
     */
    LandRuleMutationService(LandAuthorisationRepository repository,
            LandAuthorisationCache cache, Clock clock, PublicEvents events,
            java.util.function.Supplier<
                    java.util.concurrent.CompletionStage<
                            LandAuthorisationRepository.SnapshotData>> reloader) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = events == null ? PublicEvents.noop() : events;
        this.reloader = Objects.requireNonNull(reloader, "reloader");
    }

    /** Live runtime snapshot holder fed by every publish here. */
    public LandAuthorisationCache cache() {
        return cache;
    }

    /**
     * Persist one per-land environment rule ({@code INHERIT} deletes the
     * row), then publish the rebuilt snapshot and fire the change event.
     */
    public CompletionStage<Void> setRule(UUID actor, LandId landId,
            LandRuleType rule, PermissionState state) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(state, "state");
        CompletionStage<PermissionState> committed;
        try {
            committed = repository.setRule(landId, rule, state, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("rule repository returned null"));
        }
        return committed.thenCompose(before -> refresh()
                .thenAccept(ignored -> fireRuleChanged(actor, landId, rule, before, state))
                .<Void>exceptionally(publishFailure -> {
                    // The commit stands but the publish failed (the snapshot went
                    // fail-closed inside refresh, and no event fired): surface a
                    // distinct result carrying the committed transition so callers
                    // can tell this apart from a write that never happened.
                    throw new RulePublishException(actor, landId, rule, before, state,
                            publishFailure);
                }));
    }

    /**
     * Persist one per-land environment rule only when the durable current
     * still equals {@code expectedCurrent} (a missing row reads as
     * {@code INHERIT}) and the durable authorisation generation still
     * equals {@code expectedRevision}, then publish the rebuilt snapshot
     * and fire the change event. A stale expectation or a lapsed
     * generation fails without writing, publishing or emitting, so a
     * confirm page built from an older read can never silently overwrite
     * a newer change.
     */
    public CompletionStage<Void> setRuleExpected(UUID actor, LandId landId,
            LandRuleType rule, PermissionState expectedCurrent, long expectedRevision,
            PermissionState state) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(state, "state");
        CompletionStage<PermissionState> committed;
        try {
            committed = repository.setRuleIfCurrent(landId, rule, expectedCurrent,
                    expectedRevision, state, actor, clock.instant());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (committed == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("rule repository returned null"));
        }
        return committed.thenCompose(before -> refresh()
                .thenAccept(ignored -> fireRuleChanged(actor, landId, rule, before, state))
                .<Void>exceptionally(publishFailure -> {
                    // Same committed-but-unpublished distinction as above.
                    throw new RulePublishException(actor, landId, rule, before, state,
                            publishFailure);
                }));
    }

    /**
     * Fire the public rule Post after commit plus publish. Never
     * throws: construction and listener failures are isolated by the
     * facade, so the committed mutation still stands.
     */
    private void fireRuleChanged(UUID actor, LandId landId, LandRuleType rule,
            PermissionState before, PermissionState state) {
        try {
            PermissionState previous = before == PermissionState.INHERIT ? null : before;
            RuleChangedEvent event = new RuleChangedEvent(actor, landId, rule, previous, state);
            events.firePost(event, () -> new RuleChangedBukkitEvent(event.actorUuid(),
                    event.landId(), event.rule(), event.previous(), event.current(), true));
        } catch (Throwable ignored) {
            // Post dispatch must never break the committed rule path.
        }
    }

    /**
     * Re-read the durable rows and publish once, without writing anything.
     * Used at startup and for explicit refreshes; a failed load publishes
     * the unloaded marker instead of keeping a previous snapshot, so a
     * stale view can never authorise after the durable rows moved.
     */
    public CompletionStage<Void> refresh() {
        CompletionStage<LandAuthorisationRepository.SnapshotData> loaded;
        try {
            loaded = reloader.get();
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
                    // another refresh path published. Rules travel in the same
                    // load and publish as the grants, so one publish carries
                    // one durable generation for both.
                    cache.publish(cache.snapshot().withDirect(
                            data.directAllows(), data.landDefaults(), data.entryBans(),
                            data.landRules(), data.landPolicyRevisions()));
                })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        cache.publish(LandAuthorisationSnapshot.unloaded());
                    }
                });
    }
}
