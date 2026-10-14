package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.ChunkLandEventListener;
import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.mutation.ExpectedVersion;
import com.smile.chunkland.api.mutation.MutationOutcome;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.mutation.TypedMutationResult;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimSaga;
import com.smile.chunkland.claim.DeleteSaga;
import com.smile.chunkland.claim.ExpandSaga;
import com.smile.chunkland.claim.ShrinkSaga;
import com.smile.chunkland.claim.WorldClaimPolicy;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.limit.LimitResolver;
import com.smile.chunkland.limit.OwnerQuotaService;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.profile.PermissionProfileService;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.rename.LandRenameService;
import com.smile.chunkland.rule.LandRuleMutationService;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import com.smile.chunkland.runtime.mutation.LogicalReservationRegistry;
import com.smile.chunkland.runtime.mutation.ProductionMutationPorts;
import com.smile.chunkland.runtime.mutation.SagaBackedMutations;
import com.smile.chunkland.selection.SelectionClock;
import com.smile.chunkland.selection.SelectionNotifier;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import com.smile.chunkland.selection.SelectionTimeoutScheduler;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public typed mutations cover the defined permission changes and the
 * per-land rule changes through the production services: the same
 * trust, default, ban, binding and rule writes the chat and management
 * paths run, with the shared management gate, the expected
 * authorisation generation and the ledger idempotency enforced first.
 */
class PublicAuthorisationMutationTest {

    @TempDir java.nio.file.Path temp;

    private static final Currency EMC = Currency.of("EMC", 2);
    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class FakeEconomy implements ClaimEconomy {
        @Override
        public CompletionStage<ChargeResult> charge(UUID operationId,
                com.smile.chunkland.claim.ClaimRequest request, Money price) {
            return CompletableFuture.completedFuture(ChargeResult.ok());
        }

        @Override
        public CompletionStage<RefundOutcome> refund(LedgerEntry entry) {
            return CompletableFuture.completedFuture(RefundOutcome.REFUNDED);
        }

        @Override
        public String providerId() {
            return "test-economy";
        }
    }

    static final class RecordingBus implements ChunkLandEventBus {
        final List<Object> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public <E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        }

        @Override
        public <E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener) {
        }

        @Override
        public <E> void publish(E event) {
            events.add(event);
        }

        long permissionEvents(PermissionChangedEvent.ChangeKind kind) {
            return events.stream().filter(event -> event instanceof PermissionChangedEvent change
                    && change.kind() == kind).count();
        }

        long ruleEvents() {
            return events.stream().filter(event -> event instanceof RuleChangedEvent).count();
        }
    }

    final class Harness implements AutoCloseable {
        final PersistenceStore store;
        final OperationLedger ledger;
        final LandRegistryStore registryStore = new LandRegistryStore();
        final FakeEconomy economy = new FakeEconomy();
        final RecordingBus bus = new RecordingBus();
        final PublicEvents events = PublicEvents.create(bus, null);
        final SelectionSessionManager selections;
        final Set<UUID> stewards = ConcurrentHashMap.newKeySet();
        final Set<UUID> bypassed = ConcurrentHashMap.newKeySet();
        final LandAuthorisationService authorisations;
        final LandRuleMutationService ruleService;
        final LandBindingService bindingService;
        final PermissionProfileService profiles;
        final SagaBackedMutations facade;

        Harness() throws Exception {
            store = PersistenceStore.open(temp.resolve(UUID.randomUUID() + ".db"));
            ledger = new OperationLedger(store);
            OwnerQuotaService quotas = new OwnerQuotaService(new LimitResolver(
                    new ChunkLandConfig(Map.of(), new LimitSettings(10, 100, 128, 16), 0L, Map.of())));
            LogicalReservationRegistry reservations = new LogicalReservationRegistry();
            SqliteLandRepository lands = new SqliteLandRepository(store);
            SqliteChunkRepository chunks = new SqliteChunkRepository(store);
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(lands, registryStore);
            SelectionStructureRevisionLookup liveStructures = landId -> {
                try {
                    var snapshot = registryStore.snapshot();
                    if (snapshot == null) {
                        return OptionalLong.empty();
                    }
                    var land = snapshot.land(landId);
                    return land == null
                            ? OptionalLong.empty()
                            : OptionalLong.of(land.structureRevision());
                } catch (RuntimeException unresolved) {
                    return OptionalLong.empty();
                }
            };
            selections = new SelectionSessionManager(
                    (playerId, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                    new SelectionVisualizationTaskController() {
                        @Override
                        public void stop(UUID playerId) {
                        }
                    },
                    SelectionNotifier.noop(),
                    (SelectionClock) () -> NOW,
                    Duration.ofMinutes(10),
                    ignored -> Optional.of("world"),
                    liveStructures);
            PricingTable pricing = PricingTable.of(List.of(
                    PricingTier.of(5, new Money(100, EMC)),
                    PricingTier.of(PricingTier.UNBOUNDED, new Money(200, EMC))));
            ClaimSaga claimSaga = ChunkLandPlugin.buildClaimSaga(registryStore, selections,
                    quotas, pricing, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, WorldClaimPolicy.allowAll(), events);
            ExpandSaga expandSaga = ChunkLandPlugin.buildExpandSaga(registryStore, selections,
                    quotas, pricing, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, WorldClaimPolicy.allowAll(), events);
            ShrinkSaga shrinkSaga = ChunkLandPlugin.buildShrinkSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, EMC, events);
            DeleteSaga deleteSaga = ChunkLandPlugin.buildDeleteSaga(registryStore, selections,
                    quotas, reservations, ledger, economy, rebuilder, Runnable::run,
                    liveStructures, chunks, events);
            LandRenameService renameService = ChunkLandPlugin.buildLandRename(
                    store, registryStore, rebuilder);
            authorisations = ChunkLandPlugin.buildLandAuthorisations(store, events);
            ruleService = ChunkLandPlugin.buildLandRules(store, authorisations.cache(), events);
            bindingService = new LandBindingService(new LandBindingRepository(store),
                    authorisations.cache(), CLOCK, events);
            profiles = new PermissionProfileService(new PermissionProfileRepository(store), CLOCK);
            authorisations.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            bindingService.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
            SubLandMutationRunner sublandRunner = ChunkLandPlugin.buildSubLandRunner(store,
                    registryStore, selections, new SubLandConfirmService(),
                    ChunkLandPlugin.buildSubLandDepthSource(registryStore),
                    new LimitSettings(10, 100, 128, 16), Clock.systemUTC(), events, null, null);
            facade = new SagaBackedMutations(
                    request -> {
                        throw new IllegalStateException("legacy must stay rejected");
                    },
                    Set.of(),
                    claimSaga, expandSaga, shrinkSaga, deleteSaga, renameService,
                    ProductionMutationPorts.sublands(sublandRunner, Clock.systemUTC()),
                    ProductionMutationPorts.permissions(authorisations, bindingService),
                    ProductionMutationPorts.rules(ruleService),
                    authorisations.cache()::snapshot,
                    ledger,
                    registryStore::snapshot,
                    () -> new SnapshotPermissionContextProvider(null, null),
                    stewards::contains,
                    bypassed::contains,
                    CLOCK);
        }

        TypedMutationResult call(TypedMutationRequest request) throws Exception {
            return facade.submitTyped(request).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }

        LandId createParent(UUID actor, OwnerRef owner, UUID world) throws Exception {
            TypedMutationResult created = call(new TypedMutationRequest.LandCreate(
                    actor, owner, null, null, UUID.randomUUID(),
                    world, chunksOf(world, 0, 0, 1, 0), "Parent"));
            assertEquals(MutationOutcome.SUCCESS, created.outcome());
            return created.landId();
        }

        long policyOf(LandId landId) {
            return authorisations.cache().snapshot().policyRevision(landId).orElse(0L);
        }

        TypedMutationRequest.PermissionChange subjectChange(UUID actor, OwnerRef owner,
                LandId landId, long policy, UUID operationId,
                PermissionChangedEvent.ChangeKind kind, UUID subject) {
            return new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, ExpectedVersion.policy(policy), operationId, null,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject, null, null, null);
        }

        TypedMutationRequest.PermissionChange defaultChange(UUID actor, OwnerRef owner,
                LandId landId, long policy, UUID operationId, ProtectionActionType action,
                PermissionState state) {
            return new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, ExpectedVersion.policy(policy), operationId, null,
                    PermissionChangedEvent.ChangeKind.DEFAULT_SET, null, null,
                    action, state, null);
        }

        TypedMutationRequest.PermissionChange bindChange(UUID actor, OwnerRef owner,
                LandId landId, SubLandId subland, long policy, UUID operationId,
                PermissionChangedEvent.ChangeKind kind, UUID subject, UUID profile) {
            return new TypedMutationRequest.PermissionChange(
                    actor, owner, landId, ExpectedVersion.policy(policy), operationId, subland,
                    kind, PermissionChangedEvent.SubjectKind.PLAYER, subject, null, null, profile);
        }

        boolean ledgerHas(UUID operationId) throws Exception {
            return ledger.findById(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent();
        }

        @Override
        public void close() {
            store.close();
        }
    }

    private static Set<ChunkKey> chunksOf(UUID world, int... coords) {
        if (coords.length % 2 != 0) {
            throw new IllegalArgumentException("coords must be x/z pairs");
        }
        java.util.LinkedHashSet<ChunkKey> out = new java.util.LinkedHashSet<>();
        for (int i = 0; i < coords.length; i += 2) {
            out.add(new ChunkKey(world, coords[i], coords[i + 1]));
        }
        return Collections.unmodifiableSet(out);
    }

    @Test
    void trustAndUntrustCommitThroughTheRealService() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult trusted = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.TRUST, friend));
            assertEquals(MutationOutcome.SUCCESS, trusted.outcome());
            assertEquals(land, trusted.landId());
            assertTrue(h.authorisations.cache().snapshot().trustedPlayers(land).contains(friend));
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.TRUST));

            TypedMutationResult untrusted = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.UNTRUST, friend));
            assertEquals(MutationOutcome.SUCCESS, untrusted.outcome());
            assertTrue(h.authorisations.cache().snapshot().trustedPlayers(land).isEmpty());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.UNTRUST));
        }
    }

    @Test
    void banAndUnbanCommitThroughTheRealService() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID rival = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult banned = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.BAN, rival));
            assertEquals(MutationOutcome.SUCCESS, banned.outcome());
            assertTrue(h.authorisations.cache().snapshot().bannedPlayers(land).contains(rival));

            TypedMutationResult unbanned = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.UNBAN, rival));
            assertEquals(MutationOutcome.SUCCESS, unbanned.outcome());
            assertTrue(h.authorisations.cache().snapshot().bannedPlayers(land).isEmpty());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.BAN));
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.UNBAN));
        }
    }

    @Test
    void defaultSetAndBackToInheritCommit() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult allowed = h.call(h.defaultChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    ProtectionActionType.CONTAINER_OPEN, PermissionState.ALLOW));
            assertEquals(MutationOutcome.SUCCESS, allowed.outcome());
            assertEquals(PermissionState.ALLOW, h.authorisations.cache().snapshot()
                    .landDefault(land, ProtectionActionType.CONTAINER_OPEN));

            TypedMutationResult inherit = h.call(h.defaultChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    ProtectionActionType.CONTAINER_OPEN, PermissionState.INHERIT));
            assertEquals(MutationOutcome.SUCCESS, inherit.outcome());
            assertEquals(PermissionState.INHERIT, h.authorisations.cache().snapshot()
                    .landDefault(land, ProtectionActionType.CONTAINER_OPEN));
            assertEquals(2, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.DEFAULT_SET));
        }
    }

    @Test
    void bindAndUnbindLandWithARealProfile() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            UUID profile = h.profiles.create(actor, "Crew").toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).id();
            TypedMutationResult bound = h.call(h.bindChange(actor, OwnerRef.player(actor),
                    land, null, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.BIND, member, profile));
            assertEquals(MutationOutcome.SUCCESS, bound.outcome());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.BIND));

            TypedMutationResult unbound = h.call(h.bindChange(actor, OwnerRef.player(actor),
                    land, null, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.UNBIND, member, null));
            assertEquals(MutationOutcome.SUCCESS, unbound.outcome());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.UNBIND));
        }
    }

    @Test
    void bindAndUnbindSublandScope() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            Cuboid cuboid = new Cuboid(0, 64, 0, 15, 320, 15);
            TypedMutationResult subland = h.call(new TypedMutationRequest.SublandCreate(
                    actor, OwnerRef.player(actor), land,
                    ExpectedVersion.structure(
                            h.registryStore.snapshot().land(land).structureRevision()),
                    UUID.randomUUID(), world, "Cellar",
                    cuboid.coveredChunks(world), cuboid));
            assertEquals(MutationOutcome.SUCCESS, subland.outcome());
            SubLandId target = h.registryStore.snapshot().land(land).subLands().get(0).id();
            UUID profile = h.profiles.create(actor, "Crew").toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).id();
            TypedMutationResult bound = h.call(h.bindChange(actor, OwnerRef.player(actor),
                    null, target, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.BIND, member, profile));
            assertEquals(MutationOutcome.SUCCESS, bound.outcome());
            assertEquals(land, bound.landId());

            TypedMutationResult unbound = h.call(h.bindChange(actor, OwnerRef.player(actor),
                    null, target, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.UNBIND, member, null));
            assertEquals(MutationOutcome.SUCCESS, unbound.outcome());
        }
    }

    @Test
    void permissionWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID owner = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(owner, OwnerRef.player(owner), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.subjectChange(owner, OwnerRef.server(),
                    land, h.policyOf(land), operationId,
                    PermissionChangedEvent.ChangeKind.TRUST, friend));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("permission.owner_mismatch", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(0, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.TRUST));
            assertTrue(h.authorisations.cache().snapshot().trustedPlayers(land).isEmpty());
        }
    }

    @Test
    void ruleWithStaleClaimedOwnerRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID owner = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(owner, OwnerRef.player(owner), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.RuleChange(
                    owner, OwnerRef.server(), land, ExpectedVersion.policy(h.policyOf(land)),
                    operationId, LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("rule.owner_mismatch", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(0, h.bus.ruleEvents());
            assertEquals(PermissionState.INHERIT,
                    h.authorisations.cache().snapshot().landRule(land, LandRuleType.PVP));
        }
    }

    @Test
    void strangerCannotChangePermissionsOrRules() throws Exception {
        try (Harness h = new Harness()) {
            UUID owner = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(owner, OwnerRef.player(owner), world);
            UUID trustId = UUID.randomUUID();
            TypedMutationResult trust = h.call(h.subjectChange(stranger, OwnerRef.player(owner),
                    land, h.policyOf(land), trustId,
                    PermissionChangedEvent.ChangeKind.TRUST, stranger));
            assertEquals(MutationOutcome.REJECTED, trust.outcome());
            assertTrue(!h.ledgerHas(trustId));

            UUID ruleId = UUID.randomUUID();
            TypedMutationResult rule = h.call(new TypedMutationRequest.RuleChange(
                    stranger, OwnerRef.player(owner), land, ExpectedVersion.policy(h.policyOf(land)),
                    ruleId, LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.REJECTED, rule.outcome());
            assertTrue(!h.ledgerHas(ruleId));
            assertEquals(0, h.bus.ruleEvents());
        }
    }

    @Test
    void serverLandPermissionAndRuleSpoofsStayRejected() throws Exception {
        try (Harness h = new Harness()) {
            UUID steward = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            h.stewards.add(steward);
            LandId land = h.createParent(steward, OwnerRef.server(), world);

            UUID strangerId = UUID.randomUUID();
            TypedMutationResult strangerTrust = h.call(h.subjectChange(stranger, OwnerRef.server(),
                    land, h.policyOf(land), strangerId,
                    PermissionChangedEvent.ChangeKind.TRUST, stranger));
            assertEquals(MutationOutcome.REJECTED, strangerTrust.outcome());
            assertTrue(!h.ledgerHas(strangerId));

            UUID stewardId = UUID.randomUUID();
            TypedMutationResult stewardTrust = h.call(h.subjectChange(steward, OwnerRef.server(),
                    land, h.policyOf(land), stewardId,
                    PermissionChangedEvent.ChangeKind.TRUST, stranger));
            assertEquals(MutationOutcome.REJECTED, stewardTrust.outcome());
            assertEquals("permission.server-unsupported", stewardTrust.diagnosticKey());
            assertTrue(!h.ledgerHas(stewardId));

            UUID ruleId = UUID.randomUUID();
            TypedMutationResult rule = h.call(new TypedMutationRequest.RuleChange(
                    steward, OwnerRef.server(), land, ExpectedVersion.policy(h.policyOf(land)),
                    ruleId, LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.REJECTED, rule.outcome());
            assertEquals("rule.server-unsupported", rule.diagnosticKey());
            assertTrue(!h.ledgerHas(ruleId));
            assertEquals(0, h.bus.ruleEvents());
        }
    }

    @Test
    void landOnlyKindsOnASublandScopeAreRejectedSpecifically() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            SubLandId missing = new SubLandId(UUID.randomUUID());
            for (PermissionChangedEvent.ChangeKind kind : new PermissionChangedEvent.ChangeKind[]{
                    PermissionChangedEvent.ChangeKind.TRUST,
                    PermissionChangedEvent.ChangeKind.UNTRUST,
                    PermissionChangedEvent.ChangeKind.BAN,
                    PermissionChangedEvent.ChangeKind.UNBAN}) {
                UUID operationId = UUID.randomUUID();
                TypedMutationResult result = h.call(new TypedMutationRequest.PermissionChange(
                        actor, OwnerRef.player(actor), null, ExpectedVersion.policy(h.policyOf(land)),
                        operationId, missing, kind,
                        PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(),
                        null, null, null));
                assertEquals(MutationOutcome.REJECTED, result.outcome());
                assertEquals("permission.subland-unsupported", result.diagnosticKey());
                assertTrue(!h.ledgerHas(operationId));
            }
            UUID defaultId = UUID.randomUUID();
            TypedMutationResult def = h.call(new TypedMutationRequest.PermissionChange(
                    actor, OwnerRef.player(actor), null, ExpectedVersion.policy(h.policyOf(land)),
                    defaultId, missing, PermissionChangedEvent.ChangeKind.DEFAULT_SET, null, null,
                    ProtectionActionType.CONTAINER_OPEN, PermissionState.ALLOW, null));
            assertEquals(MutationOutcome.REJECTED, def.outcome());
            assertEquals("permission.subland-unsupported", def.diagnosticKey());
            assertTrue(!h.ledgerHas(defaultId));
        }
    }

    @Test
    void groupSubjectOnPlayerKindsIsRejectedSpecifically() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(new TypedMutationRequest.PermissionChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(h.policyOf(land)),
                    operationId, null, PermissionChangedEvent.ChangeKind.TRUST,
                    PermissionChangedEvent.SubjectKind.GROUP, UUID.randomUUID(), null, null, null));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("permission.unsupported-subject", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
        }
    }

    @Test
    void defaultWithManagementActionIsRejectedSpecifically() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.defaultChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), operationId,
                    ProtectionActionType.MANAGE_PERMISSION, PermissionState.ALLOW));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("permission.unknown_action", result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
        }
    }

    @Test
    void subjectChangeCarryingAProfileFailsFastAtConstruction() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TypedMutationRequest.PermissionChange(
                actor, OwnerRef.player(actor), new LandId(UUID.randomUUID()),
                ExpectedVersion.policy(0), UUID.randomUUID(), null,
                PermissionChangedEvent.ChangeKind.TRUST,
                PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(), null, null,
                UUID.randomUUID()));
    }

    @Test
    void stalePolicyRevisionRejectsWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            long live = h.policyOf(land);
            UUID operationId = UUID.randomUUID();
            TypedMutationResult result = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, live + 5, operationId,
                    PermissionChangedEvent.ChangeKind.TRUST, friend));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, result.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(0, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.TRUST));
        }
    }

    @Test
    void expiredConfirmFailsWithoutANewWriteOrEvent() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            long pinned = h.policyOf(land);
            TypedMutationResult committed = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, pinned, UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.TRUST, first));
            assertEquals(MutationOutcome.SUCCESS, committed.outcome());
            TypedMutationResult stale = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, pinned, UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.TRUST, second));
            assertEquals(MutationOutcome.REJECTED, stale.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, stale.diagnosticKey());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.TRUST));
            assertTrue(h.authorisations.cache().snapshot().trustedPlayers(land).contains(first));
            assertTrue(!h.authorisations.cache().snapshot().trustedPlayers(land).contains(second));
        }
    }

    @Test
    void staleDefaultRevisionRejectsAtTheFacadeWithoutSideEffects() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            long pinned = h.policyOf(land);
            TypedMutationResult allowed = h.call(h.defaultChange(actor, OwnerRef.player(actor),
                    land, pinned, UUID.randomUUID(),
                    ProtectionActionType.CONTAINER_OPEN, PermissionState.ALLOW));
            assertEquals(MutationOutcome.SUCCESS, allowed.outcome());
            UUID operationId = UUID.randomUUID();
            TypedMutationResult stale = h.call(h.defaultChange(actor, OwnerRef.player(actor),
                    land, pinned, operationId,
                    ProtectionActionType.CONTAINER_OPEN, PermissionState.DENY));
            assertEquals(MutationOutcome.REJECTED, stale.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, stale.diagnosticKey());
            assertTrue(!h.ledgerHas(operationId));
            assertEquals(PermissionState.ALLOW, h.authorisations.cache().snapshot()
                    .landDefault(land, ProtectionActionType.CONTAINER_OPEN));
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.DEFAULT_SET));
        }
    }

    @Test
    void banningTheOwnerFailsClosed() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult result = h.call(h.subjectChange(actor, OwnerRef.player(actor),
                    land, h.policyOf(land), UUID.randomUUID(),
                    PermissionChangedEvent.ChangeKind.BAN, actor));
            assertEquals(MutationOutcome.REJECTED, result.outcome());
            assertEquals("permission.invalid", result.diagnosticKey());
            assertEquals(0, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.BAN));
            assertTrue(h.authorisations.cache().snapshot().bannedPlayers(land).isEmpty());
        }
    }

    @Test
    void sameTrustIdResubmittedConvergesWithoutDuplicateEvents() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.PermissionChange trust = h.subjectChange(actor,
                    OwnerRef.player(actor), land, h.policyOf(land), operationId,
                    PermissionChangedEvent.ChangeKind.TRUST, friend);
            TypedMutationResult first = h.call(trust);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(trust);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(land, second.landId());
            assertEquals(1, h.bus.permissionEvents(PermissionChangedEvent.ChangeKind.TRUST));
        }
    }

    @Test
    void ruleChangeCommitsAndFiresExactlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult result = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(h.policyOf(land)),
                    UUID.randomUUID(), LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.SUCCESS, result.outcome());
            assertEquals(land, result.landId());
            assertEquals(PermissionState.DENY,
                    h.authorisations.cache().snapshot().landRule(land, LandRuleType.PVP));
            assertEquals(1, h.bus.ruleEvents());
        }
    }

    @Test
    void ruleBackToInheritDeletesTheOverride() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            TypedMutationResult denied = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(h.policyOf(land)),
                    UUID.randomUUID(), LandRuleType.FIRE_SPREAD, PermissionState.DENY));
            assertEquals(MutationOutcome.SUCCESS, denied.outcome());
            TypedMutationResult inherit = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(h.policyOf(land)),
                    UUID.randomUUID(), LandRuleType.FIRE_SPREAD, PermissionState.INHERIT));
            assertEquals(MutationOutcome.SUCCESS, inherit.outcome());
            assertEquals(PermissionState.INHERIT,
                    h.authorisations.cache().snapshot().landRule(land, LandRuleType.FIRE_SPREAD));
            assertEquals(2, h.bus.ruleEvents());
        }
    }

    @Test
    void expiredRuleConfirmWritesNothingPublishesNothingEmitsNothing() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            long pinned = h.policyOf(land);
            TypedMutationResult committed = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(pinned),
                    UUID.randomUUID(), LandRuleType.PVP, PermissionState.DENY));
            assertEquals(MutationOutcome.SUCCESS, committed.outcome());
            TypedMutationResult stale = h.call(new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(pinned),
                    UUID.randomUUID(), LandRuleType.PVP, PermissionState.ALLOW));
            assertEquals(MutationOutcome.REJECTED, stale.outcome());
            assertEquals(TypedMutationResult.STALE_EXPECTED_VERSION, stale.diagnosticKey());
            assertEquals(PermissionState.DENY,
                    h.authorisations.cache().snapshot().landRule(land, LandRuleType.PVP));
            assertEquals(1, h.bus.ruleEvents());
        }
    }

    @Test
    void sameRuleIdResubmittedFiresOnlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            UUID actor = UUID.randomUUID();
            UUID world = UUID.randomUUID();
            LandId land = h.createParent(actor, OwnerRef.player(actor), world);
            UUID operationId = UUID.randomUUID();
            TypedMutationRequest.RuleChange change = new TypedMutationRequest.RuleChange(
                    actor, OwnerRef.player(actor), land, ExpectedVersion.policy(h.policyOf(land)),
                    operationId, LandRuleType.MOB_GRIEFING, PermissionState.DENY);
            TypedMutationResult first = h.call(change);
            assertEquals(MutationOutcome.SUCCESS, first.outcome());
            TypedMutationResult second = h.call(change);
            assertEquals(MutationOutcome.SUCCESS, second.outcome());
            assertEquals(1, h.bus.ruleEvents());
            assertEquals(PermissionState.DENY, h.authorisations.cache().snapshot()
                    .landRule(land, LandRuleType.MOB_GRIEFING));
        }
    }
}
