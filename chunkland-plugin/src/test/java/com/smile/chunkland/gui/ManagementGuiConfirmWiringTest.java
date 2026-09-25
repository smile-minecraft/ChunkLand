package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.smile.chunkland.command.LandDefaultCommandHandler;
import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.persistence.LandDefaultConflictException;
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
 * Confirm-toggle wiring for the second management layer.
 *
 * <p>A second-layer row click must open a confirm page (current state shown,
 * target state shown); confirming writes exactly once through the
 * expected-current mutation seam and redraws the details page, while
 * cancel, back, a denied gate and a failed write never write anything.
 * Async completions hop back to the region thread, double submits collapse
 * to one write, management-class rows stay view-only, and a stale confirm
 * is refused instead of overwriting a newer change.
 */
class ManagementGuiConfirmWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());
    private static final ProtectionActionType ROW = ProtectionActionType.BLOCK_BREAK;

    record MutationCall(UUID actor, LandId landId, ProtectionActionType action,
            PermissionState expected, long expectedRevision, PermissionState state) {
    }

    enum SchedulerMode {
        DIRECT,
        QUEUED
    }

    @Test
    void rowClickOpensConfirmPage() throws Exception {
        Fixture fixture = fixture(emptyAuth());

        long rootGeneration = fixture.plugin().openManagementGui(OWNER, LAND).orElseThrow();
        GuiNavigator navigator = fixture.plugin().guiNavigator().orElseThrow();
        navigator.handleClick(OWNER, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        long detailsGeneration = navigator.currentGeneration(OWNER).orElseThrow();

        navigator.handleClick(OWNER, detailsGeneration, rowSlot(navigator));

        assertEquals(3, navigator.depth(OWNER),
                "row click must push the confirm page on top of root and details");
        assertEquals("chunkland:land-manage-confirm",
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void confirmWritesOnceWithTargetStateAndRedrawsDetails() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        long rootGeneration = fixture.plugin().openManagementGui(OWNER, LAND).orElseThrow();
        GuiNavigator navigator = fixture.plugin().guiNavigator().orElseThrow();
        navigator.handleClick(OWNER, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                rowSlot(navigator));
        long confirmGeneration = navigator.currentGeneration(OWNER).orElseThrow();
        GuiPage confirm = navigator.currentPage(OWNER).orElseThrow();

        navigator.handleClick(OWNER, confirmGeneration, ManagementGuiPages.CONFIRM_SLOT);

        assertEquals(1, fixture.mutations().size(), "confirm must write exactly once");
        MutationCall call = fixture.mutations().get(0);
        assertEquals(OWNER, call.actor());
        assertEquals(LAND, call.landId());
        assertEquals(ROW, call.action());
        assertEquals(PermissionState.INHERIT, call.expected(),
                "the write must carry the observed current");
        assertEquals(0L, call.expectedRevision(),
                "the write must pin the gate-time land revision");
        assertEquals(PermissionState.DENY, call.state(),
                "unset land default toggles to DENY");
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id(),
                "success must redraw the details page, got: " + confirm.id());
    }

    @Test
    void writesSucceedWhenRegistryStaleButAuthCacheFresh() throws Exception {
        AtomicReference<LandAuthorisationSnapshot> auth = new AtomicReference<>(
                LandAuthorisationSnapshot.copyOf(Map.of(), Map.of())
                        .withLandPolicyRevisions(Map.of(LAND, 7L)));
        Fixture fixture = fixture(auth);
        fixture.durableRevision().set(7L);
        GuiNavigator navigator = openThroughConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);

        assertEquals(1, fixture.mutations().size(),
                "a fresh authorisation generation must write even when the structural"
                        + " registry snapshot still carries the claim-time revision");
        assertEquals(7L, fixture.mutations().get(0).expectedRevision());
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void cancelWritesNothingAndReturnsToDetails() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CANCEL_SLOT);

        assertTrue(fixture.mutations().isEmpty(), "cancel must never write");
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void backWritesNothingAndReturnsToPreviousPage() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        GuiNavigator navigator = openThroughConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.BACK_SLOT);

        assertTrue(fixture.mutations().isEmpty(), "back must never write");
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
    }

    @Test
    void deniedGateAtConfirmWritesNothingAndStaysOnConfirm() throws Exception {
        UUID stranger = UUID.randomUUID();
        AtomicReference<LandAuthorisationSnapshot> auth = new AtomicReference<>(
                grantedSnapshot(stranger));
        Fixture fixture = fixture(auth);
        GuiNavigator navigator = openThroughConfirm(fixture, stranger);

        fixture.setAuth(LandAuthorisationSnapshot.unloaded());

        navigator.handleClick(stranger, navigator.currentGeneration(stranger).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);

        assertTrue(fixture.mutations().isEmpty(), "denied gate must never write");
        assertEquals("chunkland:land-manage-confirm",
                navigator.currentPage(stranger).orElseThrow().id(),
                "denied gate must stay on the confirm page");
    }

    @Test
    void failedWriteWritesNothingSuccessfulAndStaysOnConfirm() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        fixture.failWrites(true);
        GuiNavigator navigator = openThroughConfirm(fixture);

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);

        assertTrue(fixture.mutations().isEmpty(), "failed write must not succeed");
        assertEquals("chunkland:land-manage-confirm",
                navigator.currentPage(OWNER).orElseThrow().id(),
                "failed write must stay on the confirm page");
    }

    @Test
    void confirmCompletionHopsToRegionThread() throws Exception {
        Fixture fixture = fixture(emptyAuth(),
                SchedulerMode.QUEUED);
        GuiNavigator navigator = openThroughConfirm(fixture);
        fixture.holdStages(true);
        Thread regionThread = Thread.currentThread();

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);
        assertEquals(1, fixture.attempts().size(), "confirm must submit exactly once");
        CompletableFuture<Void> pending = fixture.pendingStages().get(0);

        AtomicReference<Thread> completingThread = new AtomicReference<>();
        Thread persistence = new Thread(() -> {
            completingThread.set(Thread.currentThread());
            pending.complete(null);
        }, "fake-persistence");
        persistence.start();
        persistence.join();

        assertEquals("chunkland:land-manage-confirm",
                navigator.currentPage(OWNER).orElseThrow().id(),
                "nothing may redraw before the region hop runs");
        assertEquals(1, fixture.regionQueue().size(), "completion must hop exactly once");
        assertEquals(completingThread.get(), fixture.handoffThreads().get(0));
        assertNotEquals(regionThread, completingThread.get());

        fixture.drainRegion();
        assertEquals(regionThread, fixture.runThreads().get(0),
                "the redraw must run on the region thread, not the completing thread");
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());
        assertEquals(1, fixture.mutations().size());
    }

    @Test
    void doubleConfirmSubmitsOnlyOnce() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        AtomicReference<LandAuthorisationSnapshot> auth = fixture.auth();
        GuiNavigator navigator = openThroughConfirm(fixture);
        fixture.holdStages(true);

        long confirmGeneration = navigator.currentGeneration(OWNER).orElseThrow();
        navigator.handleClick(OWNER, confirmGeneration, ManagementGuiPages.CONFIRM_SLOT);
        navigator.handleClick(OWNER, confirmGeneration, ManagementGuiPages.CONFIRM_SLOT);

        assertEquals(1, fixture.attempts().size(),
                "a second confirm while the first is in flight must not resubmit");
        fixture.pendingStages().get(0).complete(null);
        assertEquals(1, fixture.mutations().size());
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID,
                navigator.currentPage(OWNER).orElseThrow().id());

        fixture.setAuth(LandAuthorisationSnapshot.copyOf(Map.of(),
                Map.of(LAND, Map.of(ROW, PermissionState.DENY)))
                .withLandPolicyRevisions(Map.of(LAND, 0L)));
        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                rowSlot(navigator, OWNER));
        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);
        assertEquals(2, fixture.attempts().size(),
                "the guard must release after completion");
        fixture.pendingStages().get(1).complete(null);
        assertEquals(2, fixture.mutations().size());
        assertEquals(PermissionState.ALLOW, fixture.mutations().get(1).state());
    }

    @Test
    void managementRowClickOpensNoConfirmPage() throws Exception {
        Fixture fixture = fixture(emptyAuth());
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(OWNER, LAND).orElseThrow();
        navigator.handleClick(OWNER, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        long detailsGeneration = navigator.currentGeneration(OWNER).orElseThrow();
        int managementSlot = rowSlotFor(navigator, OWNER, ProtectionActionType.DELETE_LAND);

        navigator.handleClick(OWNER, detailsGeneration, managementSlot);

        assertEquals(2, navigator.depth(OWNER),
                "management rows must stay view-only");
        assertTrue(fixture.attempts().isEmpty(), "management rows must never write");
    }

    @Test
    void staleConfirmRefusesWriteAndRebuildsConfirm() throws Exception {
        AtomicReference<LandAuthorisationSnapshot> auth = new AtomicReference<>(
                emptyAuth());
        Fixture fixture = fixture(auth);
        GuiNavigator navigator = openThroughConfirm(fixture);

        fixture.durable().set(PermissionState.DENY);
        fixture.setAuth(LandAuthorisationSnapshot.copyOf(Map.of(),
                Map.of(LAND, Map.of(ROW, PermissionState.DENY)))
                .withLandPolicyRevisions(Map.of(LAND, 0L)));

        navigator.handleClick(OWNER, navigator.currentGeneration(OWNER).orElseThrow(),
                ManagementGuiPages.CONFIRM_SLOT);

        assertEquals(1, fixture.attempts().size(), "the stale write must be attempted once");
        assertTrue(fixture.mutations().isEmpty(), "a stale confirm must never commit");
        GuiPage current = navigator.currentPage(OWNER).orElseThrow();
        assertEquals("chunkland:land-manage-confirm", current.id(),
                "a stale confirm must stay on the confirm page");
        String lines = String.join("\n", current.lines());
        assertTrue(lines.contains("fail-closed"), "stale confirm must show the failure line");
        assertTrue(lines.contains("DENY") && lines.contains("ALLOW"),
                "the rebuilt page must show the fresh current and target, got:\n" + lines);
    }

    private static GuiNavigator openThroughConfirm(Fixture fixture) throws Exception {
        return openThroughConfirm(fixture, OWNER);
    }

    private static GuiNavigator openThroughConfirm(Fixture fixture, UUID actor)
            throws Exception {
        ChunkLandPlugin plugin = fixture.plugin();
        GuiNavigator navigator = plugin.guiNavigator().orElseThrow();
        long rootGeneration = plugin.openManagementGui(actor, LAND).orElseThrow();
        navigator.handleClick(actor, rootGeneration, ManagementGuiPages.ENTRY_SLOT);
        navigator.handleClick(actor, navigator.currentGeneration(actor).orElseThrow(),
                rowSlot(navigator, actor));
        assertEquals("chunkland:land-manage-confirm",
                navigator.currentPage(actor).orElseThrow().id());
        return navigator;
    }

    private static int rowSlot(GuiNavigator navigator) {
        return rowSlot(navigator, OWNER);
    }

    private static int rowSlot(GuiNavigator navigator, UUID actor) {
        GuiPage details = navigator.currentPage(actor).orElseThrow();
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID, details.id());
        List<Integer> slots = new ArrayList<>(details.slots());
        for (int slot : slots) {
            if (slot == ManagementGuiPages.BACK_SLOT) {
                continue;
            }
            Optional<GuiButton> button = details.buttonAt(slot);
            if (button.isPresent()) {
                return slot;
            }
        }
        throw new IllegalStateException("details page carries no row button");
    }

    private static int rowSlotFor(GuiNavigator navigator, UUID actor,
            ProtectionActionType action) {
        GuiPage details = navigator.currentPage(actor).orElseThrow();
        assertEquals(ManagementGuiPages.DETAILS_PAGE_ID, details.id());
        for (GuiPage.RenderItem item : details.renderItems()) {
            if (item.name().startsWith(action.name() + ":")) {
                return item.slot();
            }
        }
        throw new IllegalStateException("details page carries no row for " + action);
    }

    private static LandAuthorisationSnapshot emptyAuth() {
        return LandAuthorisationSnapshot.copyOf(Map.of(), Map.of())
                .withLandPolicyRevisions(Map.of(LAND, 0L));
    }

    private static LandAuthorisationSnapshot grantedSnapshot(UUID actor) {
        return LandAuthorisationSnapshot.copyOf(Map.of(), Map.of(), Map.of(),
                Map.of(LAND, List.of(new PermissionBinding(
                        PermissionSubject.player(actor),
                        new Permission(ProtectionActionType.MANAGE_PERMISSION,
                                PermissionState.ALLOW)))),
                Map.of(), Map.of(), Map.of());
    }

    private static Fixture fixture(LandAuthorisationSnapshot auth) throws Exception {
        return fixture(new AtomicReference<>(auth), SchedulerMode.DIRECT);
    }

    private static Fixture fixture(LandAuthorisationSnapshot auth, SchedulerMode mode)
            throws Exception {
        return fixture(new AtomicReference<>(auth), mode);
    }

    private static Fixture fixture(AtomicReference<LandAuthorisationSnapshot> auth)
            throws Exception {
        return fixture(auth, SchedulerMode.DIRECT);
    }

    private static Fixture fixture(AtomicReference<LandAuthorisationSnapshot> auth,
            SchedulerMode mode) throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        FakeGuiService service = new FakeGuiService();
        LandRegistryStore store = new LandRegistryStore();
        store.publish(LandRegistry.from(List.of(land())));
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
        Queue<Runnable> regionQueue = new ConcurrentLinkedQueue<>();
        List<Thread> handoffThreads = new CopyOnWriteArrayList<>();
        List<Thread> runThreads = new CopyOnWriteArrayList<>();
        PlayerScheduler scheduler;
        if (mode == SchedulerMode.QUEUED) {
            scheduler = (player, task) -> {
                handoffThreads.add(Thread.currentThread());
                regionQueue.add(task);
            };
        } else {
            scheduler = PlayerScheduler.direct();
        }
        setField(plugin, "managementGuiScheduler", scheduler);
        List<MutationCall> attempts = new CopyOnWriteArrayList<>();
        List<MutationCall> mutations = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> pendingStages = new CopyOnWriteArrayList<>();
        AtomicReference<Boolean> failWrites = new AtomicReference<>(false);
        AtomicReference<Boolean> holdStages = new AtomicReference<>(false);
        AtomicReference<PermissionState> durable =
                new AtomicReference<>(PermissionState.INHERIT);
        AtomicLong durableRevision = new AtomicLong(0L);
        LandDefaultCommandHandler.DefaultMutation mutation =
                (actor, landId, action, expected, expectedRevision, state) -> {
                    MutationCall call = new MutationCall(actor, landId, action, expected,
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
                    if (durable.get() != expected) {
                        return CompletableFuture.failedFuture(
                                new LandDefaultConflictException("stale expected " + expected
                                        + ", durable " + durable.get() + " for " + action));
                    }
                    if (holdStages.get()) {
                        CompletableFuture<Void> pending = new CompletableFuture<>();
                        pendingStages.add(pending);
                        pending.thenRun(() -> {
                            durable.set(state);
                            mutations.add(call);
                        });
                        return pending;
                    }
                    durable.set(state);
                    mutations.add(call);
                    return CompletableFuture.completedFuture(null);
                };
        setField(plugin, "managementDefaultMutation", mutation);
        LandAuthorisationCache authCache = new LandAuthorisationCache();
        authCache.publish(auth.get());
        setField(plugin, "landAuthorisationCache", authCache);
        Fixture fixture = new Fixture(plugin, service, attempts, mutations, pendingStages,
                failWrites, holdStages, durable, durableRevision, auth, authCache, regionQueue,
                handoffThreads, runThreads);
        return fixture;
    }

    private static LandSnapshot land() {
        return new LandSnapshot(LAND, "Home", LandName.normalize("Home"),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                ManagementGuiConfirmWiringTest.class.getClassLoader(),
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
        logger.set(plugin, Logger.getLogger(ManagementGuiConfirmWiringTest.class.getName()));
        return plugin;
    }

    private record Fixture(ChunkLandPlugin plugin, FakeGuiService service,
            List<MutationCall> attempts, List<MutationCall> mutations,
            List<CompletableFuture<Void>> pendingStages, AtomicReference<Boolean> failWrites,
            AtomicReference<Boolean> holdStages, AtomicReference<PermissionState> durable,
            AtomicLong durableRevision, AtomicReference<LandAuthorisationSnapshot> auth,
            LandAuthorisationCache authCache, Queue<Runnable> regionQueue,
            List<Thread> handoffThreads, List<Thread> runThreads) {
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

        void drainRegion() {
            Runnable task;
            while ((task = regionQueue.poll()) != null) {
                runThreads.add(Thread.currentThread());
                task.run();
            }
        }
    }
}
