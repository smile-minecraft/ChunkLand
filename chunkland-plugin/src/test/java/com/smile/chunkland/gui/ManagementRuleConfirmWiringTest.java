package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.ChunkLandPlugin;
import com.smile.chunkland.adapter.gui.GuiContentRenderer;
import com.smile.chunkland.adapter.AceLibBridge;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.command.LandRuleCommandHandler;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.persistence.LandRuleConflictException;
import com.smile.chunkland.persistence.StaleAuthorisationException;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Rule confirm wiring behind the management GUI.
 *
 * <p>A rule row click opens a three-target confirm page (current explicit,
 * effective and source shown); confirming writes exactly once through the
 * conditional rule mutation with the observed explicit value and the
 * authorisation generation pinned, then redraws the rule list. Cancel,
 * back, a denied gate and a failed write never write anything. A repeated
 * submit collapses to one write, a stale confirm is refused instead of
 * overwriting a newer change, and submitting after walking to another
 * land never touches that land.
 */
class ManagementRuleConfirmWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final LandId LAND_TWO = new LandId(UUID.randomUUID());
    private static final LandRuleType RULE = LandRuleType.PVP;

    record MutationCall(UUID actor, LandId landId, LandRuleType rule,
            PermissionState expected, long expectedRevision, PermissionState state) {
    }

    @Test
    void ruleEntryOpensRuleDetails() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(OWNER, LAND).orElseThrow();

        navigator.handleClick(OWNER, rootGeneration, ManagementRulePages.RULE_ENTRY_SLOT);

        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void ruleRowClickOpensThreeTargetConfirm() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
        GuiPage confirm = navigator.currentPage(OWNER).orElseThrow();
        assertTrue(confirm.buttonAt(ManagementRulePages.RULE_ALLOW_SLOT).isPresent());
        assertTrue(confirm.buttonAt(ManagementRulePages.RULE_DENY_SLOT).isPresent());
        assertTrue(confirm.buttonAt(ManagementRulePages.RULE_INHERIT_SLOT).isPresent());
    }

    @Test
    void confirmWritesOnceWithPinnedBaselineAndRedrawsRules() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);

        assertEquals(1, fixture.mutations().size(), "confirm must write exactly once");
        MutationCall call = fixture.mutations().get(0);
        assertEquals(OWNER, call.actor());
        assertEquals(LAND, call.landId());
        assertEquals(RULE, call.rule());
        assertEquals(PermissionState.INHERIT, call.expected(),
                "the write must carry the observed explicit value");
        assertEquals(0L, call.expectedRevision(),
                "the write must pin the gate-time authorisation generation");
        assertEquals(PermissionState.DENY, call.state());
        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id(),
                "success must redraw the rule list");
    }

    @Test
    void everyRuleRowConfirmsItsOwnRule() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(OWNER, LAND).orElseThrow();
        navigator.handleClick(OWNER, rootGeneration, ManagementRulePages.RULE_ENTRY_SLOT);
        int slot = 0;
        for (LandRuleType rule : LandRuleType.values()) {
            final int rowSlot = slot++;
            navigator.handleClick(OWNER,
                    navigator.currentGeneration(OWNER).orElseThrow(), rowSlot);
            assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID,
                    navigator.currentPage(OWNER).orElseThrow().id());
            navigator.handleClick(OWNER,
                    navigator.currentGeneration(OWNER).orElseThrow(),
                    ManagementRulePages.RULE_ALLOW_SLOT);
            assertEquals(rule, fixture.mutations().get(fixture.mutations().size() - 1).rule(),
                    "row " + rowSlot + " must confirm " + rule);
        }
        assertEquals(11, fixture.mutations().size(), "every rule must be settable");
    }

    @Test
    void cancelAndBackWriteNothingAndReturnToRules() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementRulePages.RULE_CANCEL_SLOT);
        assertTrue(fixture.mutations().isEmpty(), "cancel must never write");
        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(), 0);
        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.BACK_SLOT);
        assertTrue(fixture.mutations().isEmpty(), "back must never write");
    }

    @Test
    void deniedGateAtConfirmWritesNothingAndStaysOnConfirm() throws Exception {
        UUID stranger = UUID.randomUUID();
        Fixture fixture = fixture(grantedSnapshot(stranger));
        GuiNavigator navigator = openThroughRuleConfirm(fixture, stranger);

        // A real revoke (the grant is gone, the generation still reads), so
        // only the submit-time gate can refuse this write.
        fixture.setAuth(emptyAuth());

        navigator.handleClick(stranger, navigator.currentGeneration(stranger).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);

        assertTrue(fixture.mutations().isEmpty(),
                "a revoked grant must never write through a stale confirm");
        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID,
                navigator.currentPage(stranger).orElseThrow().id(),
                "denied gate must stay on the confirm page");
    }

    @Test
    void reopenAfterRealRevokeShowsNoRuleValues() throws Exception {
        UUID stranger = UUID.randomUUID();
        Fixture fixture = fixture(grantedSnapshot(stranger));
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(stranger, LAND).orElseThrow();
        navigator.handleClick(stranger, rootGeneration, ManagementRulePages.RULE_ENTRY_SLOT);
        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(stranger).orElseThrow().id());
        assertTrue(String.join("\n", navigator.currentPage(stranger).orElseThrow().lines())
                .contains("PVP"), "the granted list must show rule values");

        // The grant is revoked while the menu is open.
        fixture.setAuth(emptyAuth());

        assertTrue(navigator.back(stranger), "back must return to the root page");
        navigator.handleClick(stranger,
                navigator.currentGeneration(stranger).orElseThrow(),
                ManagementRulePages.RULE_ENTRY_SLOT);

        GuiPage reopened = navigator.currentPage(stranger).orElseThrow();
        assertEquals(ManagementRulePages.RULE_UNAVAILABLE_PAGE_ID, reopened.id(),
                "a revoked viewer must not see the rule list again");
        assertTrue(fixture.mutations().isEmpty(), "reopening must never write");
        assertFalse(String.join("\n", reopened.lines()).contains("PVP"),
                "the denied list must not leak rule values");
    }

    @Test
    void redrawAfterRevokeDuringFlightShowsNoRuleValues() throws Exception {
        UUID stranger = UUID.randomUUID();
        Fixture fixture = fixture(grantedSnapshot(stranger));
        GuiNavigator navigator = openThroughRuleConfirm(fixture, stranger);
        fixture.holdStages(true);

        navigator.handleClick(stranger, navigator.currentGeneration(stranger).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);
        assertEquals(1, fixture.attempts().size(), "the confirm must be submitted once");

        // The grant is revoked while the write is still in flight: the
        // commit lands, but the redraw must not repaint rule values.
        fixture.setAuth(emptyAuth());
        fixture.pendingStages().get(0).complete(null);

        assertEquals(1, fixture.mutations().size(), "the admitted write still commits");
        GuiPage redrawn = navigator.currentPage(stranger).orElseThrow();
        assertEquals(ManagementRulePages.RULE_UNAVAILABLE_PAGE_ID, redrawn.id(),
                "the redraw after revoke must not repaint rule values");
        assertFalse(String.join("\n", redrawn.lines()).contains("PVP"),
                "the redrawn page must not leak rule values");
    }

    @Test
    void doubleConfirmSubmitsOnlyOnce() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);
        fixture.holdStages(true);

        long confirmGeneration = navigator.currentGeneration(OWNER).orElseThrow();
        navigator.handleClick(OWNER, confirmGeneration, ManagementRulePages.RULE_DENY_SLOT);
        navigator.handleClick(OWNER, confirmGeneration, ManagementRulePages.RULE_DENY_SLOT);

        assertEquals(1, fixture.attempts().size(),
                "a second confirm while the first is in flight must not resubmit");
        fixture.pendingStages().get(0).complete(null);
        assertEquals(1, fixture.mutations().size());
        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void staleConfirmRefusesWriteAndRebuildsConfirm() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        // Someone else changed the rule after the confirm was opened: the
        // durable value moved on, so the pinned expectation is stale.
        fixture.durable().put(RULE, PermissionState.ALLOW);
        fixture.durableRevision().set(1L);
        fixture.setAuth(LandAuthorisationSnapshot.empty()
                .withDirect(Map.of(), Map.of(), Map.of(),
                        Map.of(LAND, Map.of(RULE, PermissionState.ALLOW)),
                        Map.of(LAND, 1L, LAND_TWO, 0L)));

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);

        assertEquals(1, fixture.attempts().size(), "the stale write must be attempted once");
        assertTrue(fixture.mutations().isEmpty(), "a stale confirm must never commit");
        GuiPage current = navigator.currentPage(OWNER).orElseThrow();
        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID, current.id(),
                "a stale confirm must stay on the confirm page");
        String lines = String.join("\n", current.lines());
        assertTrue(lines.contains("nothing was modified"),
                "stale confirm must show the failure line");
        assertTrue(lines.contains("explicitly ALLOW"),
                "the rebuilt page must show the fresh explicit value, got:\n" + lines);
    }

    @Test
    void submitAfterWalkingAwayNeverTouchesTheNewLand() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);

        assertEquals(1, fixture.mutations().size());
        assertEquals(LAND, fixture.mutations().get(0).landId(),
                "the pinned confirm must write the land it was opened for");
        for (MutationCall call : fixture.mutations()) {
            assertTrue(!call.landId().equals(LAND_TWO),
                    "walking to another land must never retarget the write there");
        }
    }

    @Test
    void deletedLandFailsClosedWithoutWriting() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughRuleConfirm(fixture);

        LandRegistryStore store = fixture.store();
        store.publish(LandRegistry.from(List.of(landTwo())));

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementRulePages.RULE_DENY_SLOT);

        assertTrue(fixture.mutations().isEmpty(),
                "a confirm for a deleted land must never write");
        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    private static GuiNavigator openThroughRuleConfirm(Fixture fixture) throws Exception {
        return openThroughRuleConfirm(fixture, OWNER);
    }

    private static GuiNavigator openThroughRuleConfirm(Fixture fixture, UUID actor)
            throws Exception {
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(actor, LAND).orElseThrow();
        navigator.handleClick(actor, rootGeneration, ManagementRulePages.RULE_ENTRY_SLOT);
        assertEquals(ManagementRulePages.RULES_PAGE_ID,
                navigator.currentPage(actor).orElseThrow().id());
        navigator.handleClick(actor, navigator.currentGeneration(actor).orElseThrow(), 0);
        assertEquals(ManagementRulePages.RULE_CONFIRM_PAGE_ID,
                navigator.currentPage(actor).orElseThrow().id());
        return navigator;
    }

    private static LandAuthorisationSnapshot emptyAuth() {
        return LandAuthorisationSnapshot.copyOf(Map.of(), Map.of())
                .withLandPolicyRevisions(Map.of(LAND, 0L, LAND_TWO, 0L));
    }

    private static LandAuthorisationSnapshot grantedSnapshot(UUID actor) {
        return LandAuthorisationSnapshot.copyOf(Map.of(), Map.of(), Map.of(),
                Map.of(LAND, List.of(new PermissionBinding(
                        PermissionSubject.player(actor),
                        new Permission(ProtectionActionType.MANAGE_PERMISSION,
                                PermissionState.ALLOW)))),
                Map.of(), Map.of(), Map.of())
                .withLandPolicyRevisions(Map.of(LAND, 0L, LAND_TWO, 0L));
    }

    private static Fixture fixture(LandAuthorisationSnapshot auth) throws Exception {
        return fixture(new AtomicReference<>(auth));
    }

    private static Fixture fixture(AtomicReference<LandAuthorisationSnapshot> auth)
            throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        FakeGuiService service = new FakeGuiService();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land(), landTwo())));
        PermissionDefaultsCache defaults = new PermissionDefaultsCache(
                ChunkLandConfig::defaults, ignored -> Optional.empty(), null);
        defaults.attachLandAuthorisation(auth::get);
        GuiContentRenderer renderer = new GuiContentRenderer(service, ignored -> player(),
                material -> null);
        setField(plugin, "guiNavigator", new GuiNavigator(service));
        setField(plugin, "guiContentRenderer", renderer);
        setField(plugin, "protectionStore", store);
        setField(plugin, "permissionDefaults", defaults);
        Function<UUID, Player> players = uuid -> player();
        setField(plugin, "managementGuiPlayerLookup", players);
        setField(plugin, "managementGuiScheduler", PlayerScheduler.direct());
        List<MutationCall> attempts = new CopyOnWriteArrayList<>();
        List<MutationCall> mutations = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> pendingStages = new CopyOnWriteArrayList<>();
        AtomicReference<Boolean> failWrites = new AtomicReference<>(false);
        AtomicReference<Boolean> holdStages = new AtomicReference<>(false);
        Map<LandRuleType, PermissionState> durableRules = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicLong durableRevision = new AtomicLong(0L);
        LandRuleCommandHandler.RuleMutation mutation =
                (actor, landId, rule, expected, expectedRevision, state) -> {
                    MutationCall call = new MutationCall(actor, landId, rule, expected,
                            expectedRevision, state);
                    attempts.add(call);
                    if (failWrites.get()) {
                        return CompletableFuture.failedFuture(new RuntimeException("store boom"));
                    }
                    if (durableRevision.get() != expectedRevision) {
                        return CompletableFuture.failedFuture(
                                new StaleAuthorisationException("stale revision "
                                        + expectedRevision + ", durable "
                                        + durableRevision.get() + " for " + landId));
                    }
                    PermissionState durable = durableRules.getOrDefault(rule,
                            PermissionState.INHERIT);
                    if (durable != expected) {
                        return CompletableFuture.failedFuture(
                                new LandRuleConflictException("stale expected " + expected
                                        + ", durable " + durable + " for " + rule));
                    }
                    if (holdStages.get()) {
                        CompletableFuture<Void> pending = new CompletableFuture<>();
                        pendingStages.add(pending);
                        pending.thenRun(() -> {
                            durableRules.put(rule, state);
                            mutations.add(call);
                        });
                        return pending;
                    }
                    durableRules.put(rule, state);
                    mutations.add(call);
                    return CompletableFuture.completedFuture(null);
                };
        setField(plugin, "managementRuleMutation", mutation);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(auth.get());
        setField(plugin, "landAuthorisationCache", authCache);
        return new Fixture(plugin, store, attempts, mutations, pendingStages,
                failWrites, holdStages, durableRules, durableRevision, auth, authCache);
    }

    private static LandSnapshot land() {
        return new LandSnapshot(LAND, "Home", LandName.normalize("Home"),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandSnapshot landTwo() {
        return new LandSnapshot(LAND_TWO, "Second", LandName.normalize("Second"),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 4, 4)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                ManagementRuleConfirmWiringTest.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return OWNER;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return 0;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (Field field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == Optional.class && value == null) {
                unsafe.putObject(plugin, offset, Optional.empty());
            } else if (field.getType() == AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new AceLibBridge());
            }
        }
        Field logger = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        logger.setAccessible(true);
        logger.set(plugin, Logger.getLogger(ManagementRuleConfirmWiringTest.class.getName()));
        return plugin;
    }

    private record Fixture(ChunkLandPlugin plugin, LandRegistryStore store,
            List<MutationCall> attempts, List<MutationCall> mutations,
            List<CompletableFuture<Void>> pendingStages, AtomicReference<Boolean> failWrites,
            AtomicReference<Boolean> holdStages, Map<LandRuleType, PermissionState> durable,
            AtomicLong durableRevision, AtomicReference<LandAuthorisationSnapshot> auth,
            LandAuthorisationCache authCache) {
        void failWrites(boolean value) {
            failWrites.set(value);
        }

        void holdStages(boolean value) {
            holdStages.set(value);
        }

        void setAuth(LandAuthorisationSnapshot snapshot) {
            auth.set(snapshot);
            authCache.publish(snapshot);
        }
    }
}
