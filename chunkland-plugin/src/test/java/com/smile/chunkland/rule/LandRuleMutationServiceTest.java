package com.smile.chunkland.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.RuleChangedBukkitEvent;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.LandAuthorisationRepository.SnapshotData;
import com.smile.chunkland.persistence.LandRuleConflictException;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.StaleAuthorisationException;
import com.smile.chunkland.protection.LandAuthorisationCache;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-land rule mutations: one durable commit plus one snapshot publish, then
 * exactly one {@link RuleChangedEvent}; stale or lapsed confirmations change
 * nothing and fire nothing.
 */
class LandRuleMutationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandRuleMutationService service;
        final LandAuthorisationCache cache = new LandAuthorisationCache();
        final PublicEventBus bus = new PublicEventBus();
        final AtomicInteger calls = new AtomicInteger();
        final List<RuleChangedEvent> events = Collections.synchronizedList(new ArrayList<>());
        final List<Event> bukkitCalls = Collections.synchronizedList(new ArrayList<>());
        final UUID world = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());

        Env(Path db) {
            this(db, null);
        }

        Env(Path db,
                java.util.function.Supplier<java.util.concurrent.CompletionStage<SnapshotData>> loader) {
            store = PersistenceStore.open(db);
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            bus.register(RuleChangedEvent.class, event -> {
                calls.incrementAndGet();
                events.add(event);
            });
            LandAuthorisationRepository repository = new LandAuthorisationRepository(store);
            service = loader == null
                    ? new LandRuleMutationService(repository,
                            cache, Clock.fixed(NOW, ZoneOffset.UTC),
                            PublicEvents.create(bus, bukkitCalls::add))
                    : new LandRuleMutationService(repository,
                            cache, Clock.fixed(NOW, ZoneOffset.UTC),
                            PublicEvents.create(bus, bukkitCalls::add), loader);
        }

        private long revision() {
            return cache.snapshot().policyRevision(land).orElseThrow();
        }

        private PermissionState durableRule(LandRuleType rule) {
            SnapshotData data = new LandAuthorisationRepository(store).loadSnapshotData()
                    .toCompletableFuture().join();
            return data.landRules().getOrDefault(land, Map.of())
                    .getOrDefault(rule, PermissionState.INHERIT);
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void setRuleCommitsPublishesAndFiresOnce() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();

            assertEquals(PermissionState.DENY,
                    env.cache.snapshot().landRule(env.land, LandRuleType.PVP),
                    "the published snapshot must carry the committed rule");
            assertEquals(1, env.calls.get(), "exactly one API event per mutation");
            assertEquals(1, env.bukkitCalls.size(), "exactly one Bukkit event per mutation");
            RuleChangedEvent event = env.events.get(0);
            assertEquals(env.owner, event.actorUuid());
            assertEquals(env.land, event.landId());
            assertEquals(LandRuleType.PVP, event.rule());
            assertEquals(null, event.previous(), "no override existed before");
            assertEquals(PermissionState.DENY, event.current());
            assertTrue(env.bukkitCalls.get(0) instanceof RuleChangedBukkitEvent);
            RuleChangedBukkitEvent view = (RuleChangedBukkitEvent) env.bukkitCalls.get(0);
            assertEquals(env.land, view.landId());
            assertEquals(LandRuleType.PVP, view.rule());
            assertEquals(PermissionState.DENY, view.current());
        }
    }

    @Test
    void changeCarriesPrevious() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.ALLOW)
                    .toCompletableFuture().join();
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();

            assertEquals(2, env.calls.get());
            RuleChangedEvent second = env.events.get(1);
            assertEquals(PermissionState.ALLOW, second.previous());
            assertEquals(PermissionState.DENY, second.current());
        }
    }

    @Test
    void inheritRemovalFiresWithInheritCurrent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.INHERIT)
                    .toCompletableFuture().join();

            assertEquals(PermissionState.INHERIT, env.durableRule(LandRuleType.PVP),
                    "INHERIT must delete the override row");
            assertEquals(PermissionState.INHERIT,
                    env.cache.snapshot().landRule(env.land, LandRuleType.PVP));
            assertEquals(2, env.calls.get());
            RuleChangedEvent removal = env.events.get(1);
            assertEquals(PermissionState.DENY, removal.previous());
            assertEquals(PermissionState.INHERIT, removal.current());
        }
    }

    @Test
    void staleExpectedFailsWithoutPublishOrEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();
            env.calls.set(0);
            var published = env.cache.snapshot();

            CompletionException conflict = assertThrows(CompletionException.class, () ->
                    env.service.setRuleExpected(env.owner, env.land, LandRuleType.PVP,
                            PermissionState.ALLOW, env.revision(), PermissionState.ALLOW)
                            .toCompletableFuture().join());
            assertTrue(conflict.getCause() instanceof LandRuleConflictException,
                    "stale expected must fail as a rule conflict, got: " + conflict.getCause());

            assertSame(published, env.cache.snapshot(),
                    "a refused write must not republish the snapshot");
            assertEquals(0, env.calls.get(), "a refused write must fire no event");
            assertEquals(PermissionState.DENY, env.durableRule(LandRuleType.PVP),
                    "a refused write must keep the durable row");
        }
    }

    @Test
    void lapsedRevisionFailsWithoutPublishOrEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();
            long stale = env.revision();
            env.calls.set(0);
            // An unrelated authorisation write moves the generation forward.
            new LandAuthorisationRepository(env.store).setDefault(env.land,
                    com.smile.chunkland.api.permission.ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW, env.owner, NOW).toCompletableFuture().join();
            var published = env.cache.snapshot();

            CompletionException lapsed = assertThrows(CompletionException.class, () ->
                    env.service.setRuleExpected(env.owner, env.land, LandRuleType.PVP,
                            PermissionState.DENY, stale, PermissionState.ALLOW)
                            .toCompletableFuture().join());
            assertTrue(lapsed.getCause() instanceof StaleAuthorisationException,
                    "lapsed generation must fail as stale authorisation, got: "
                            + lapsed.getCause());

            assertSame(published, env.cache.snapshot(),
                    "a lapsed write must not republish the snapshot");
            assertEquals(0, env.calls.get(), "a lapsed write must fire no event");
            assertEquals(PermissionState.DENY,
                    env.cache.snapshot().landRule(env.land, LandRuleType.PVP));
        }
    }

    @Test
    void consecutiveUpdatesNeedFreshPins() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();
            long first = env.revision();
            env.service.setRuleExpected(env.owner, env.land, LandRuleType.PVP,
                    PermissionState.DENY, first, PermissionState.ALLOW)
                    .toCompletableFuture().join();

            CompletionException stale = assertThrows(CompletionException.class, () ->
                    env.service.setRuleExpected(env.owner, env.land, LandRuleType.PVP,
                            PermissionState.DENY, first, PermissionState.DENY)
                            .toCompletableFuture().join());
            assertTrue(stale.getCause() instanceof StaleAuthorisationException,
                    "reusing the first generation must fail, got: " + stale.getCause());
            assertEquals(PermissionState.ALLOW,
                    env.cache.snapshot().landRule(env.land, LandRuleType.PVP));
        }
    }

    @Test
    void failedCommitFiresNothing() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            var published = env.cache.snapshot();
            try {
                env.service.setRule(env.owner, new LandId(UUID.randomUUID()),
                        LandRuleType.PVP, PermissionState.DENY).toCompletableFuture().join();
                throw new AssertionError("rule write on unknown land must fail");
            } catch (CompletionException expected) {
                // Durable failure: no publish, no event.
            }
            assertSame(published, env.cache.snapshot());
            assertEquals(0, env.calls.get());
            assertEquals(0, env.bukkitCalls.size());
        }
    }

    @Test
    void throwingListenerDoesNotRollBackRule() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.bus.register(RuleChangedEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });
            env.service.setRule(env.owner, env.land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();

            assertEquals(1, env.calls.get());
            assertEquals(PermissionState.DENY,
                    env.cache.snapshot().landRule(env.land, LandRuleType.PVP),
                    "the rule stands despite the broken listener");
        }
    }

    @Test
    void refreshLoadsDurableRulesIntoSnapshot() {
        Path path = tmp.resolve(UUID.randomUUID() + ".db");
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            new LandAuthorisationRepository(store).setRule(land, LandRuleType.PVP,
                    PermissionState.DENY, owner, NOW).toCompletableFuture().join();
        }
        // Reopen: the startup path reloads the durable rows into the snapshot.
        try (PersistenceStore store = PersistenceStore.open(path)) {
            LandAuthorisationCache cache = new LandAuthorisationCache();
            LandRuleMutationService reopened = new LandRuleMutationService(
                    new LandAuthorisationRepository(store), cache);
            reopened.refresh().toCompletableFuture().join();
            assertTrue(cache.snapshot().loaded());
            assertEquals(PermissionState.DENY, cache.snapshot().landRule(land, LandRuleType.PVP),
                    "restart must restore the durable rule into the snapshot");
        }
    }

    @Test
    void failedRefreshPublishesUnloaded() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.refresh().toCompletableFuture().join();
            assertTrue(env.cache.snapshot().loaded());
            env.store.close();

            assertThrows(Exception.class,
                    () -> env.service.refresh().toCompletableFuture().join(),
                    "reload against a closed store must fail");
            assertTrue(!env.cache.snapshot().loaded(),
                    "failed reload must replace the stale snapshot with unloaded, not keep it");
            assertEquals(0, env.calls.get(), "a failed reload writes nothing, so no event fires");
        }
    }

    @Test
    void committedButUnpublishedYieldsIdentifiableResult() {
        java.util.concurrent.CompletableFuture<SnapshotData> brokenRefresh =
                new java.util.concurrent.CompletableFuture<>();
        brokenRefresh.completeExceptionally(
                new java.sql.SQLException("simulated publish-load failure"));
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"), () -> brokenRefresh)) {
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    env.service.setRule(env.owner, env.land, LandRuleType.PVP,
                            PermissionState.DENY).toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof RulePublishException,
                    "a committed write with a failed publish must surface as a "
                            + "RulePublishException, got: " + failure.getCause());
            RulePublishException committed = (RulePublishException) failure.getCause();
            assertEquals(env.owner, committed.actorUuid());
            assertEquals(env.land, committed.landId());
            assertEquals(LandRuleType.PVP, committed.rule());
            assertEquals(PermissionState.INHERIT, committed.before(),
                    "no override existed before the committed write");
            assertEquals(PermissionState.DENY, committed.after());
            assertEquals(0, env.calls.get(),
                    "a publish that never happened fires no event");
            assertEquals(0, env.bukkitCalls.size(),
                    "a publish that never happened fires no event");
            assertEquals(PermissionState.DENY, env.durableRule(LandRuleType.PVP),
                    "the durable row stands even though the publish failed");
            assertTrue(!env.cache.snapshot().loaded(),
                    "the failed publish still fails the snapshot closed");
        }
    }

    @Test
    void expectedWriteCommittedButUnpublishedYieldsIdentifiableResult() {
        java.util.concurrent.CompletableFuture<SnapshotData> brokenRefresh =
                new java.util.concurrent.CompletableFuture<>();
        brokenRefresh.completeExceptionally(
                new java.sql.SQLException("simulated publish-load failure"));
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"), () -> brokenRefresh)) {
            // Seed one committed rule through the repository so the expected
            // value and generation pins exist before the failing publish.
            LandAuthorisationRepository seeding = new LandAuthorisationRepository(env.store);
            assertEquals(PermissionState.INHERIT,
                    seeding.setRule(env.land, LandRuleType.PVP, PermissionState.ALLOW,
                            env.owner, NOW).toCompletableFuture().join());
            Long pinned = seeding.loadSnapshotData().toCompletableFuture().join()
                    .landPolicyRevisions().get(env.land);
            assertNotNull(pinned, "the seeded land must carry a durable generation");

            CompletionException failure = assertThrows(CompletionException.class, () ->
                    env.service.setRuleExpected(env.owner, env.land, LandRuleType.PVP,
                            PermissionState.ALLOW, pinned, PermissionState.DENY)
                            .toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof RulePublishException,
                    "a committed expected-write with a failed publish must surface as a "
                            + "RulePublishException, got: " + failure.getCause());
            RulePublishException committed = (RulePublishException) failure.getCause();
            assertEquals(env.owner, committed.actorUuid());
            assertEquals(env.land, committed.landId());
            assertEquals(LandRuleType.PVP, committed.rule());
            assertEquals(PermissionState.ALLOW, committed.before());
            assertEquals(PermissionState.DENY, committed.after());
            assertEquals(0, env.calls.get(),
                    "a publish that never happened fires no event");
            assertEquals(PermissionState.DENY, env.durableRule(LandRuleType.PVP),
                    "the durable row stands even though the publish failed");
            assertTrue(!env.cache.snapshot().loaded(),
                    "the failed publish still fails the snapshot closed");
        }
    }

    @Test
    void snapshotDataCarriesRulesForPublish() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setRule(env.owner, env.land, LandRuleType.FIRE_SPREAD,
                    PermissionState.ALLOW).toCompletableFuture().join();
            SnapshotData data = new LandAuthorisationRepository(env.store).loadSnapshotData()
                    .toCompletableFuture().join();
            assertEquals(PermissionState.ALLOW, data.landRules()
                    .getOrDefault(env.land, Map.of())
                    .getOrDefault(LandRuleType.FIRE_SPREAD, PermissionState.INHERIT));
        }
    }
}
