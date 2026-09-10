package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionResolver;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-closed ban snapshot validity: a snapshot that was never loaded, or
 * whose reload failed, must never authorise.
 *
 * <p>A fresh cache starts unloaded, so a known land answers empty from the
 * ban seam (the entry adapter treats that as banned) and every
 * subject-permission action denies at the land layer even when the
 * world/global ENTRY default allows. A failed reload replaces the previous
 * snapshot with the unloaded marker instead of keeping a stale unbanned
 * view, and the next successful reload restores normal answers. Uses a
 * closed store as the deterministic reload failure — no sleeps, no timing
 * windows.
 */
class LandAuthorisationFailClosedTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @TempDir Path tmp;

    private static LandSnapshot land(LandId id, UUID owner) {
        return new LandSnapshot(id, "Home", "home", OwnerRef.player(owner), WORLD,
                Set.of(new ChunkKey(WORLD, 0, 0)), List.of(),
                0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private static LandRegistry registry(LandId land) {
        return LandRegistry.from(List.of(land(land, OWNER)));
    }

    private static PermissionDefaultsSnapshot allowEntryDefaults() {
        return new PermissionDefaultsSnapshot(
                Map.of(ProtectionActionType.ENTRY, PermissionState.ALLOW),
                Map.of(), Map.of(), Map.of());
    }

    private static PermissionState decide(LandId land, UUID actor,
            ProtectionActionType action, SubjectPermissionLookup lookup) {
        return decideWithRules(land, actor, action, lookup, null);
    }

    private static PermissionState decideWithRules(LandId land, UUID actor,
            ProtectionActionType action, SubjectPermissionLookup lookup,
            LandRuleLookup rules) {
        SnapshotPermissionContextProvider provider =
                new SnapshotPermissionContextProvider(rules, lookup);
        return PermissionResolver.resolve(
                provider.provide(actor, land, action, registry(land))).outcome();
    }

    private static World worldProxy(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUID": return worldId;
                        case "getName": return "world";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakeWorld";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    @Test
    void snapshotValiditySemantics() {
        assertTrue(LandAuthorisationSnapshot.empty().loaded(),
                "empty() is a successful load with genuinely no data and stays usable");
        assertTrue(LandAuthorisationSnapshot.copyOf(Map.of(), Map.of()).loaded(),
                "copyOf without bans is a successful load");
        assertTrue(LandAuthorisationSnapshot.copyOf(Map.of(), Map.of(), Map.of()).loaded(),
                "copyOf with bans is a successful load");
        assertTrue(LandAuthorisationSnapshot.unloaded().loaded() == false,
                "unloaded() marks startup-before-load and failed reloads");
    }

    @Test
    void freshCacheFailsClosedForKnownLand() {
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry lands = registry(land);
        LandAuthorisationCache cache = new LandAuthorisationCache();

        assertTrue(cache.snapshot().loaded() == false,
                "a fresh cache has never loaded durable rows");

        EntryBanLookup lookup = new EntryBanLookup(() -> lands, cache::snapshot);
        assertTrue(lookup.bannedAt(STRANGER, WORLD, 0, 0).isEmpty(),
                "unloaded snapshot must not answer known-unbanned for a known land");
        assertEquals(Optional.of(false), lookup.bannedAt(STRANGER, WORLD, 9, 9),
                "wilderness has no owning land, so nobody is banned there");

        EntryProtectionAdapter adapter = new EntryProtectionAdapter(
                () -> Instant.EPOCH, Duration.ofSeconds(3), lookup,
                (world, x, z) -> true, (player, target) -> {});
        World world = worldProxy(WORLD);
        assertTrue(adapter.isBannedInside(STRANGER, new Location(world, 5, 64, 5)),
                "empty ban answer must stop movement inside the land (fail-closed)");
    }

    @Test
    void freshCacheSubjectLookupDeniesDespiteWorldAllow() {
        LandId land = new LandId(UUID.randomUUID());
        LandAuthorisationCache cache = new LandAuthorisationCache();
        SubjectPermissionLookup lookup = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults, cache::snapshot);

        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, lookup),
                "unloaded snapshot must deny ENTRY even when the world default allows");
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.BLOCK_BREAK, lookup),
                "unloaded snapshot must deny other subject actions too, not just ENTRY");

        LandRuleLookup rules = (landId, rule, snapshot) -> rule == LandRuleType.FIRE_SPREAD
                ? Optional.of(PermissionState.ALLOW) : Optional.empty();
        assertEquals(PermissionState.ALLOW,
                decideWithRules(land, STRANGER, ProtectionActionType.FIRE_SPREAD, lookup, rules),
                "land-rule actions stay on their own chain and ignore ban storage validity");
    }

    @Test
    void landAuthThrowingSupplierDeniesAtLandLayerDespiteGlobalAllow() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup lookup = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults,
                () -> {
                    throw new RuntimeException("durable backend boom");
                });
        SubjectPermissionLookup.Grant grant =
                lookup.grants(STRANGER, land, ProtectionActionType.ENTRY, registry(land));
        assertEquals(PermissionState.DENY, grant.landDefault(),
                "a throwing land authorisation source must read as unloaded and deny "
                        + "at the land layer instead of degrading to an empty grant");
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, lookup),
                "world/global ENTRY ALLOW must not admit anyone while the land source throws");
    }

    @Test
    void landAuthNullSupplierDeniesAtLandLayerDespiteGlobalAllow() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup lookup = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults, () -> null);
        SubjectPermissionLookup.Grant grant =
                lookup.grants(STRANGER, land, ProtectionActionType.ENTRY, registry(land));
        assertEquals(PermissionState.DENY, grant.landDefault(),
                "a null-returning land authorisation source must read as unloaded");
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, lookup),
                "world/global ENTRY ALLOW must not admit anyone while the land source is null");
    }

    @Test
    void landAuthThrowingSupplierLeavesLandRuleChainAlone() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup lookup = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults,
                () -> {
                    throw new RuntimeException("durable backend boom");
                });
        LandRuleLookup rules = (landId, rule, snapshot) -> rule == LandRuleType.FIRE_SPREAD
                ? Optional.of(PermissionState.ALLOW) : Optional.empty();
        assertEquals(PermissionState.ALLOW,
                decideWithRules(land, STRANGER, ProtectionActionType.FIRE_SPREAD, lookup, rules),
                "land-rule actions stay on their own chain when the land source is unavailable");
    }

    @Test
    void loadedEmptyStillLetsGlobalAllowThrough() {
        LandId land = new LandId(UUID.randomUUID());
        SubjectPermissionLookup lookup = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults,
                LandAuthorisationSnapshot::empty);
        assertEquals(PermissionState.ALLOW,
                decide(land, STRANGER, ProtectionActionType.ENTRY, lookup),
                "a successful empty load is known-unbanned and must keep letting the global default apply");
    }

    private static PermissionDefaultsCache allowEntryCache() {
        ChunkLandConfig parsed = ConfigSchema.parseYamlText(""
                + "subject-defaults:\n"
                + "  global:\n"
                + "    ENTRY: ALLOW\n");
        return new PermissionDefaultsCache(() -> parsed, name -> Optional.empty(), null);
    }

    @Test
    void defaultsCacheUnattachedDeniesDespiteGlobalAllow() {
        LandId land = new LandId(UUID.randomUUID());
        PermissionDefaultsCache cache = allowEntryCache();
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, cache.subjectLookup()),
                "an unattached land source must read as unloaded, not loaded empty");
    }

    @Test
    void defaultsCacheNullAttachAndFailingSuppliersDeny() {
        LandId land = new LandId(UUID.randomUUID());

        PermissionDefaultsCache detached = allowEntryCache();
        detached.attachLandAuthorisation(null);
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, detached.subjectLookup()),
                "attaching null must detach back to unloaded");

        PermissionDefaultsCache nullSupplier = allowEntryCache();
        nullSupplier.attachLandAuthorisation(() -> null);
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, nullSupplier.subjectLookup()),
                "a null-returning land source must deny despite the global ALLOW");

        PermissionDefaultsCache throwingSupplier = allowEntryCache();
        throwingSupplier.attachLandAuthorisation(() -> {
            throw new RuntimeException("durable backend boom");
        });
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, throwingSupplier.subjectLookup()),
                "a throwing land source must deny despite the global ALLOW");
    }

    @Test
    void defaultsCacheLoadedEmptyStillLetsGlobalAllowThrough() {
        LandId land = new LandId(UUID.randomUUID());
        PermissionDefaultsCache cache = allowEntryCache();
        cache.attachLandAuthorisation(LandAuthorisationSnapshot::empty);
        assertEquals(PermissionState.ALLOW,
                decide(land, STRANGER, ProtectionActionType.ENTRY, cache.subjectLookup()),
                "an attached successful empty load must keep letting the global default apply");
    }

    @Test
    void refreshFailureReplacesStaleSnapshotWithUnloaded() throws Exception {
        Path path = tmp.resolve("failover.db");
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry lands = registry(land);
        LandAuthorisationCache cache = new LandAuthorisationCache();
        PersistenceStore store = PersistenceStore.open(path);
        try {
            new SqliteLandRepository(store).save(land(land, OWNER))
                    .toCompletableFuture().join();
            LandAuthorisationService service =
                    new LandAuthorisationService(new LandAuthorisationRepository(store), cache);
            service.refresh().toCompletableFuture().join();
            assertTrue(cache.snapshot().loaded(),
                    "successful preload must publish a usable snapshot");

            store.close();
            assertThrows(Exception.class,
                    () -> service.refresh().toCompletableFuture().join(),
                    "reload against a closed store must fail");
            assertTrue(cache.snapshot().loaded() == false,
                    "failed reload must replace the stale snapshot with unloaded, not keep it");
        } finally {
            if (!store.isClosed()) {
                store.close();
            }
        }

        EntryBanLookup lookup = new EntryBanLookup(() -> lands, cache::snapshot);
        assertTrue(lookup.bannedAt(STRANGER, WORLD, 0, 0).isEmpty(),
                "stale unbanned view must not keep answering after the reload failed");
        SubjectPermissionLookup subjects = new ConfigSubjectPermissionLookup(
                LandAuthorisationFailClosedTest::allowEntryDefaults, cache::snapshot);
        assertEquals(PermissionState.DENY,
                decide(land, STRANGER, ProtectionActionType.ENTRY, subjects),
                "world ENTRY ALLOW must not admit anyone while the reload failure stands");
    }

    @Test
    void successfulReloadRecoversUnbannedAndBan() throws Exception {
        Path path = tmp.resolve("recover.db");
        LandId land = new LandId(UUID.randomUUID());
        LandRegistry lands = registry(land);
        LandAuthorisationCache cache = new LandAuthorisationCache();

        PersistenceStore first = PersistenceStore.open(path);
        new SqliteLandRepository(first).save(land(land, OWNER))
                .toCompletableFuture().join();
        LandAuthorisationService failing =
                new LandAuthorisationService(new LandAuthorisationRepository(first), cache);
        failing.refresh().toCompletableFuture().join();
        first.close();
        assertThrows(Exception.class,
                () -> failing.refresh().toCompletableFuture().join(),
                "reload against a closed store must fail");
        assertTrue(cache.snapshot().loaded() == false,
                "failed reload must leave the cache unloaded");

        PersistenceStore second = PersistenceStore.open(path);
        try {
            LandAuthorisationService recovered =
                    new LandAuthorisationService(new LandAuthorisationRepository(second), cache);
            recovered.refresh().toCompletableFuture().join();
            assertTrue(cache.snapshot().loaded(),
                    "successful reload must publish a usable snapshot again");

            EntryBanLookup lookup = new EntryBanLookup(() -> lands, cache::snapshot);
            assertEquals(Optional.of(false), lookup.bannedAt(STRANGER, WORLD, 0, 0),
                    "reloaded empty bans must answer known-unbanned again");
            SubjectPermissionLookup subjects = new ConfigSubjectPermissionLookup(
                    LandAuthorisationFailClosedTest::allowEntryDefaults, cache::snapshot);
            assertEquals(PermissionState.ALLOW,
                    decide(land, STRANGER, ProtectionActionType.ENTRY, subjects),
                    "reloaded snapshot must let the world default apply again");

            recovered.ban(OWNER, land, TARGET).toCompletableFuture().join();
            assertEquals(Optional.of(true),
                    new EntryBanLookup(() -> lands, cache::snapshot)
                            .bannedAt(TARGET, WORLD, 0, 0),
                    "committed ban must read banned after the reload");
            assertEquals(PermissionState.DENY,
                    decide(land, TARGET, ProtectionActionType.ENTRY, subjects),
                    "committed ban must deny ENTRY first");
        } finally {
            second.close();
        }
    }
}
