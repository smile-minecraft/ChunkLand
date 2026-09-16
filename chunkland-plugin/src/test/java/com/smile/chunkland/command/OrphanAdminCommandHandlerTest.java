package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.OperationPayload;
import com.smile.chunkland.persistence.OrphanPurgeRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteAuditRepository;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
import com.smile.chunkland.runtime.storage.OrphanWorldGuard;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Red contract for {@code /land admin orphan list} and
 * {@code /land admin orphan purge}.
 *
 * <p>Listing is read-only, asynchronous and bounded: only worlds absent from
 * the caller-captured loaded set appear. Purging needs the independent
 * {@code chunkland.admin.orphan} node (holding the ledger or serverland node
 * never grants it), a bound two-step confirmation, and deletes exactly one
 * world's Land-owned rows with one {@code ORPHAN_PURGE} audit row. Every
 * refusal — missing confirmation, replay, expiry, wrong actor/world/nonce, a
 * reappearing world or a lost race — leaves zero durable side effects, never
 * refunds, and never touches healthy worlds or the player namespace.
 */
class OrphanAdminCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    @TempDir Path temporaryDirectory;

    private record Reply(String key, Map<String, Object> vars, String threadName) {
    }

    private static final class CapturingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars, Thread.currentThread().getName()));
        }

        @Override public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    private static CommandSender senderWithPerms(Map<String, Boolean> perms, String name) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        String node = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                        if ("explode".equals(node)) {
                            throw new RuntimeException("permission backend failed");
                        }
                        return perms.getOrDefault(node, false);
                    }
                    if (method.getName().equals("getName")) {
                        return name;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static CommandSender consoleSender(Map<String, Boolean> perms) {
        return senderWithPerms(perms, "Console");
    }

    private static Player playerSender(UUID uuid, Map<String, Boolean> perms) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return uuid;
                    }
                    if (method.getName().equals("hasPermission")) {
                        String node = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                        return perms.getOrDefault(node, false);
                    }
                    if (method.getName().equals("getName")) {
                        return "Operator";
                    }
                    if (method.getName().equals("locale")) {
                        return Locale.US;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "Player-proxy";
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
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return null;
    }

    /** Scheduler fake that records the hop instead of touching Bukkit. */
    private static final class RecordingScheduler implements PlayerScheduler {
        final AtomicInteger hops = new AtomicInteger();
        final AtomicReference<String> hopThread = new AtomicReference<>();

        @Override public void runForPlayer(Player player, Runnable task) {
            hops.incrementAndGet();
            hopThread.set(Thread.currentThread().getName());
            task.run();
        }
    }

    private Path database() {
        return temporaryDirectory.resolve(UUID.randomUUID() + ".db");
    }

    private Map<String, Boolean> orphanPerms() {
        return Map.of(LandPermissions.ORPHAN, true);
    }

    private static LandSnapshot land(UUID world, LandId lid, OwnerRef owner, ChunkKey chunk) {
        com.smile.chunkland.api.land.LandName name =
                com.smile.chunkland.api.land.LandName.of("Home " + chunk.chunkX());
        return new LandSnapshot(lid, name.displayName(), name.nameKey(), owner, world, Set.of(chunk),
                List.of(), 4, 0, NOW, NOW);
    }

    /** One land with chunk, subland, trust binding, ban and default rows. */
    private LandId seedLand(PersistenceStore store, UUID world, OwnerRef owner, int chunkX)
            throws Exception {
        SqliteLandRepository lands = new SqliteLandRepository(store);
        SqliteChunkRepository chunks = new SqliteChunkRepository(store);
        SqliteSubLandRepository subs = new SqliteSubLandRepository(store);
        LandAuthorisationRepository auths = new LandAuthorisationRepository(store);
        LandId land = new LandId(UUID.randomUUID());
        ChunkKey key = new ChunkKey(world, chunkX, 0);
        lands.save(land(world, land, owner, key)).toCompletableFuture().join();
        chunks.addChunk(land, key, 64, UUID.randomUUID(), 100L).toCompletableFuture().join();
        subs.save(new SubLandSnapshot(new SubLandId(UUID.randomUUID()), land, "den",
                new Cuboid(chunkX * 16, 60, 0, chunkX * 16 + 15, 70, 15), world))
                .toCompletableFuture().join();
        auths.trust(land, UUID.randomUUID(), owner.key().startsWith("PLAYER:")
                ? UUID.fromString(owner.key().substring("PLAYER:".length())) : UUID.randomUUID(), NOW)
                .toCompletableFuture().join();
        auths.ban(land, UUID.randomUUID(), UUID.randomUUID(), NOW)
                .toCompletableFuture().join();
        auths.setDefault(land, ProtectionActionType.BLOCK_BREAK, PermissionState.DENY,
                UUID.randomUUID(), NOW).toCompletableFuture().join();
        return land;
    }

    private OrphanAdminCommandHandler handler(PersistenceStore store, Set<UUID> loaded,
            RecordingScheduler scheduler, AtomicReference<SupplierClock> clockRef,
            AtomicBoolean refreshed) {
        OrphanWorldGuard guard = new OrphanWorldGuard();
        guard.publish(loaded);
        return handlerWithGuard(store, guard, scheduler, clockRef, refreshed);
    }

    private OrphanAdminCommandHandler handlerWithGuard(PersistenceStore store, OrphanWorldGuard guard,
            RecordingScheduler scheduler, AtomicReference<SupplierClock> clockRef,
            AtomicBoolean refreshed) {
        OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
        SupplierClock time = clockRef == null ? null : clockRef.get();
        return new OrphanAdminCommandHandler(() -> repos, guard,
                time == null ? null : time::now, scheduler,
                () -> {
                    refreshed.set(true);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private static final class SupplierClock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        Instant now() {
            return now.get();
        }
    }

    private static List<Reply> awaitReplies(CapturingSink sink, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (sink.replies.size() >= expected) {
                break;
            }
            Thread.sleep(25);
        }
        return List.copyOf(sink.replies);
    }

    private static int auditCount(PersistenceStore store) throws Exception {
        AuditRepository audits = new SqliteAuditRepository(store);
        return audits.findAll(1000, 0).toCompletableFuture().get(10, TimeUnit.SECONDS).size();
    }

    private static int landCount(PersistenceStore store, UUID world) throws Exception {
        return new OrphanPurgeRepository(store).countLandsInWorld(world)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void orphanPermissionIsIndependentFromLedgerAndServerland() {
        assertEquals("chunkland.admin.orphan", LandPermissions.ORPHAN);
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.ORPHAN));
        assertNotEquals(LandPermissions.ADMIN, LandPermissions.ORPHAN);
        assertNotEquals("chunkland.admin.serverland", LandPermissions.ORPHAN);
        assertNotEquals("chunkland.admin.bypass", LandPermissions.ORPHAN);
        assertEquals("chunkland.admin.ledger", LandPermissions.forSubcommand("admin"));
    }

    @Test
    void ledgerOrServerlandHoldersWithoutOrphanAreDeniedWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            seedLand(store, orphan, owner, 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);

            Map<String, Boolean> privileged = Map.of(
                    "chunkland.admin.ledger", true,
                    "chunkland.admin.serverland", true,
                    "chunkland.admin.bypass", true);
            for (String[] args : new String[][] {
                    {"admin", "orphan", "list"},
                    {"admin", "orphan", "purge", orphan.toString()}}) {
                CapturingSink sink = new CapturingSink();
                handler(store, Set.of(), scheduler, clock, refreshed).handle(
                        consoleSender(privileged), args, sink);
                List<Reply> replies = awaitReplies(sink, 1, 5000);
                assertEquals("command.land.denied", replies.get(0).key(),
                        "ledger/serverland/bypass must never grant orphan admin");
                assertEquals(LandPermissions.ORPHAN, replies.get(0).vars().get("permission"));
            }
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
            assertTrue(!refreshed.get());
        }
    }

    @Test
    void throwingPermissionBackendFailsClosed() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            handler(store, Set.of(), scheduler, clock, refreshed).handle(
                    senderWithPerms(Map.of(), "Console"), new String[] {"admin", "orphan", "list"}, sink);
            // Sanity: a sender with no permissions is denied without touching storage.
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.denied", replies.get(0).key());

            CapturingSink failing = new CapturingSink();
            CommandSender explosive = (CommandSender) Proxy.newProxyInstance(
                    CommandSender.class.getClassLoader(),
                    new Class<?>[] {CommandSender.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("hasPermission")) {
                            throw new RuntimeException("permission backend failed");
                        }
                        return defaultValue(method.getReturnType());
                    });
            handler(store, Set.of(), scheduler, clock, refreshed).handle(
                    explosive, new String[] {"admin", "orphan", "list"}, failing);
            List<Reply> denied = awaitReplies(failing, 1, 5000);
            assertEquals("command.land.denied", denied.get(0).key());
        }
    }

    @Test
    void listShowsOnlyOrphanWorldsAndStaysBounded() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID healthy = UUID.randomUUID();
            UUID orphan = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            seedLand(store, healthy, owner, 1);
            seedLand(store, orphan, owner, 2);
            seedLand(store, orphan, owner, 3);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            UUID operator = UUID.randomUUID();
            handler(store, Set.of(healthy), scheduler, clock, refreshed).handle(
                    playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "list"}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals(1, replies.size());
            assertEquals("command.land.admin.orphan.line", replies.get(0).key());
            String value = String.valueOf(replies.get(0).vars().get("value"));
            assertTrue(value.contains(orphan.toString()));
            assertTrue(!value.contains(healthy.toString()), "healthy world must never appear");
            assertTrue(value.length() <= 220);
            assertTrue(scheduler.hops.get() >= 1, "player reply must hop through the player scheduler");
        }
    }

    @Test
    void listIsEmptyWhenNoOrphanExists() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID healthy = UUID.randomUUID();
            seedLand(store, healthy, OwnerRef.player(UUID.randomUUID()), 1);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            handler(store, Set.of(healthy), scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "list"}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.admin.orphan.empty", replies.get(0).key());
        }
    }

    @Test
    void purgeWithoutConfirmationDeletesNothing() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            LandId land = seedLand(store, orphan, owner, 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            handler(store, Set.of(), scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.admin.orphan.confirm", replies.get(0).key());
            assertNotNull(replies.get(0).vars().get("nonce"));
            assertEquals("1", String.valueOf(replies.get(0).vars().get("count")));

            assertEquals(1, landCount(store, orphan));
            assertTrue(new SqliteLandRepository(store).findById(land)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent());
            assertEquals(auditsBefore, auditCount(store));
            assertTrue(!refreshed.get());
        }
    }

    @Test
    void confirmedPurgeDeletesOwnedRowsAuditsAndRefreshes() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            UUID healthy = UUID.randomUUID();
            OwnerRef owner = OwnerRef.player(UUID.randomUUID());
            seedLand(store, orphan, owner, 1);
            LandId kept = seedLand(store, healthy, owner, 9);
            OperationLedger ledger = new OperationLedger(store);
            OperationPayload payload = OperationPayload.claim(UUID.randomUUID(), UUID.randomUUID(),
                    healthy, kept,
                    List.of(new OperationPayload.Chunk(new ChunkKey(healthy, 9, 0), 12,
                            UUID.randomUUID(), 100L)),
                    100L, "test-economy", NOW, "Home");
            ledger.create(payload).toCompletableFuture().join();

            LandRegistryStore registryStore = new LandRegistryStore();
            RuntimeRegistryRebuilder rebuilder = new RuntimeRegistryRebuilder(
                    new SqliteLandRepository(store), registryStore,
                    new SqliteChunkRepository(store));
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            UUID operator = UUID.randomUUID();
            OrphanWorldGuard guard = new OrphanWorldGuard();
            guard.publish(Set.of(healthy));
            OrphanAdminCommandHandler sut = new OrphanAdminCommandHandler(
                    () -> repos, guard,
                    clock.get()::now, scheduler, rebuilder::rebuild);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 10000);
            assertEquals("command.land.admin.orphan.purged", replies.get(0).key());

            assertEquals(0, landCount(store, orphan));
            assertEquals(1, landCount(store, healthy));
            assertTrue(new SqliteLandRepository(store).findById(kept)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).isPresent());
            AuditRepository audits = new SqliteAuditRepository(store);
            List<AuditEntry> purgeRows = audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, purgeRows.size());
            assertEquals(operator, purgeRows.get(0).actor());
            // The ledger row survives untouched: no refund, no replay.
            assertEquals("CREATED",
                    ledger.find(payload.operationId()).toCompletableFuture().join().state());
            // The rebuilt runtime no longer publishes the orphan world.
            assertTrue(registryStore.snapshot().findLand(orphan, 1, 0) == null
                    || registryStore.snapshot().worldIndex(orphan) == null);
            assertNotNull(registryStore.snapshot().findLand(healthy, 9, 0));
        }
    }

    @Test
    void wrongNonceExpiresTheConfirmationWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            awaitReplies(first, 1, 5000);

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(),
                            "confirm", UUID.randomUUID().toString()},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }

    @Test
    void expiredConfirmationIsRejectedWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            SupplierClock time = new SupplierClock();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(time);
            AtomicBoolean refreshed = new AtomicBoolean(false);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));
            time.now.set(NOW.plusSeconds(OrphanAdminCommandHandler.CONFIRM_TTL_SECONDS + 1));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }

    @Test
    void differentActorCannotReuseAConfirmation() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(UUID.randomUUID(), orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(UUID.randomUUID(), orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }

    @Test
    void replayedConfirmationIsRejected() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            assertEquals("command.land.admin.orphan.purged",
                    awaitReplies(second, 1, 10000).get(0).key());

            CapturingSink replay = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    replay);
            List<Reply> replies = awaitReplies(replay, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            AuditRepository audits = new SqliteAuditRepository(store);
            assertEquals(1, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void worldReappearingBeforeConfirmAbortsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            OrphanWorldGuard guard = new OrphanWorldGuard();
            guard.publish(Set.of());
            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut = new OrphanAdminCommandHandler(
                    () -> repos, guard, clock.get()::now, scheduler,
                    () -> {
                        refreshed.set(true);
                        return CompletableFuture.completedFuture(null);
                    });

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));
            guard.publish(Set.of(orphan));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }

    @Test
    void purgeOfUnknownWorldFailsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            handler(store, Set.of(), scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "purge", UUID.randomUUID().toString()}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals(0, auditCount(store));
        }
    }

    @Test
    void malformedWorldUuidRepliesUsage() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            CapturingSink sink = new CapturingSink();
            handler(store, Set.of(), scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "purge", "not-a-uuid"}, sink);
            List<Reply> replies = awaitReplies(sink, 1, 5000);
            assertEquals("command.land.admin.orphan.usage", replies.get(0).key());
        }
    }

    @Test
    void refreshFailureKeepsTheDeleteButNeverReportsSuccess() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            OrphanPurgeRepository repos = new OrphanPurgeRepository(store);
            UUID operator = UUID.randomUUID();
            CompletionStage<Object> broken =
                    CompletableFuture.failedFuture(new IllegalStateException("rebuild failed"));
            OrphanWorldGuard guard = new OrphanWorldGuard();
            guard.publish(Set.of());
            OrphanAdminCommandHandler sut = new OrphanAdminCommandHandler(
                    () -> repos, guard, clock.get()::now, scheduler, () -> broken);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 10000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertTrue(String.valueOf(replies.get(0).vars().get("reason")).contains("refresh"));

            // The durable delete and its audit stay deleted; nothing is restored.
            assertEquals(0, landCount(store, orphan));
            AuditRepository audits = new SqliteAuditRepository(store);
            assertEquals(1, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void concurrentConfirmsLeaveExactlyOneWinner() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));

            CapturingSink secondSink = new CapturingSink();
            CapturingSink thirdSink = new CapturingSink();
            Player firstPlayer = playerSender(operator, orphanPerms());
            Player secondPlayer = playerSender(operator, orphanPerms());
            Thread firstAttempt = new Thread(() -> sut.handle(firstPlayer,
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    secondSink));
            Thread secondAttempt = new Thread(() -> sut.handle(secondPlayer,
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    thirdSink));
            firstAttempt.start();
            secondAttempt.start();
            firstAttempt.join(10000);
            secondAttempt.join(10000);
            List<Reply> secondReplies = awaitReplies(secondSink, 1, 5000);
            List<Reply> thirdReplies = awaitReplies(thirdSink, 1, 5000);
            int wins = 0;
            if (secondReplies.get(0).key().equals("command.land.admin.orphan.purged")) {
                wins++;
            }
            if (thirdReplies.get(0).key().equals("command.land.admin.orphan.purged")) {
                wins++;
            }
            assertEquals(1, wins, "exactly one concurrent confirm must win");
            AuditRepository audits = new SqliteAuditRepository(store);
            assertEquals(1, audits.findByAction(OrphanPurgeRepository.AUDIT_ACTION, 10)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void missingGuardFailsListAndPurgeClosedWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);

            CapturingSink listed = new CapturingSink();
            handlerWithGuard(store, null, scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "list"}, listed);
            List<Reply> listReplies = awaitReplies(listed, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", listReplies.get(0).key());
            assertEquals("orphan.unavailable", listReplies.get(0).vars().get("reason"));

            CapturingSink purged = new CapturingSink();
            handlerWithGuard(store, null, scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, purged);
            List<Reply> purgeReplies = awaitReplies(purged, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", purgeReplies.get(0).key());
            assertEquals("orphan.unavailable", purgeReplies.get(0).vars().get("reason"));

            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
            assertTrue(!refreshed.get());
        }
    }

    @Test
    void unverifiedGuardFailsListAndPurgeClosedWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            OrphanWorldGuard guard = new OrphanWorldGuard();
            assertTrue(!guard.snapshot().isVerified());

            CapturingSink listed = new CapturingSink();
            handlerWithGuard(store, guard, scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "list"}, listed);
            assertEquals("command.land.admin.orphan.failed",
                    awaitReplies(listed, 1, 5000).get(0).key());

            CapturingSink purged = new CapturingSink();
            handlerWithGuard(store, guard, scheduler, clock, refreshed).handle(
                    consoleSender(orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, purged);
            assertEquals("command.land.admin.orphan.failed",
                    awaitReplies(purged, 1, 5000).get(0).key());

            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }

    @Test
    void unrelatedCatalogChangeBetweenIssueAndConfirmAbortsWithZeroSideEffect() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(new SupplierClock());
            AtomicBoolean refreshed = new AtomicBoolean(false);
            OrphanWorldGuard guard = new OrphanWorldGuard();
            guard.publish(Set.of());
            OrphanAdminCommandHandler sut =
                    handlerWithGuard(store, guard, scheduler, clock, refreshed);
            UUID operator = UUID.randomUUID();

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));
            // An unrelated world loads: the target is still absent, but the
            // catalog moved, so the bound generation no longer matches.
            guard.publish(Set.of(UUID.randomUUID()));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals("orphan.conflict", replies.get(0).vars().get("reason"));
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
            assertTrue(!refreshed.get());
        }
    }

    @Test
    void confirmationExactlyAtExpiryIsRejected() throws Exception {
        try (PersistenceStore store = PersistenceStore.open(database())) {
            UUID orphan = UUID.randomUUID();
            seedLand(store, orphan, OwnerRef.player(UUID.randomUUID()), 1);
            int auditsBefore = auditCount(store);
            RecordingScheduler scheduler = new RecordingScheduler();
            SupplierClock time = new SupplierClock();
            AtomicReference<SupplierClock> clock = new AtomicReference<>(time);
            AtomicBoolean refreshed = new AtomicBoolean(false);
            UUID operator = UUID.randomUUID();
            OrphanAdminCommandHandler sut =
                    handler(store, Set.of(), scheduler, clock, refreshed);

            CapturingSink first = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString()}, first);
            String nonce = String.valueOf(
                    awaitReplies(first, 1, 5000).get(0).vars().get("nonce"));
            time.now.set(NOW.plusSeconds(OrphanAdminCommandHandler.CONFIRM_TTL_SECONDS));

            CapturingSink second = new CapturingSink();
            sut.handle(playerSender(operator, orphanPerms()),
                    new String[] {"admin", "orphan", "purge", orphan.toString(), "confirm", nonce},
                    second);
            List<Reply> replies = awaitReplies(second, 1, 5000);
            assertEquals("command.land.admin.orphan.failed", replies.get(0).key());
            assertEquals("orphan.expired", replies.get(0).vars().get("reason"));
            assertEquals(1, landCount(store, orphan));
            assertEquals(auditsBefore, auditCount(store));
        }
    }
}
