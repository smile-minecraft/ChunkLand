package com.smile.chunkland.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.LandDefaultConflictException;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.LandAuthorisationCache;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared expected-current default writes behind the GUI confirm path and
 * the {@code /land default} command: matching expectations commit,
 * publish and emit exactly one {@code DEFAULT_SET} event; a stale
 * expectation fails without writing, publishing or emitting.
 */
class LandAuthorisationServiceExpectedDefaultTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandAuthorisationService service;
        final PublicEventBus bus = new PublicEventBus();
        final AtomicInteger calls = new AtomicInteger();
        final List<PermissionChangedEvent> events = Collections.synchronizedList(new ArrayList<>());
        final UUID world = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());

        Env(Path db) {
            store = PersistenceStore.open(db);
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            bus.register(PermissionChangedEvent.class, event -> {
                calls.incrementAndGet();
                events.add(event);
            });
            service = new LandAuthorisationService(new LandAuthorisationRepository(store),
                    new LandAuthorisationCache(), Clock.fixed(NOW, ZoneOffset.UTC),
                    PublicEvents.create(bus, null));
        }

        @Override
        public void close() {
            store.close();
        }
    }

    /**
     * Durable revision arithmetic for this fixture: {@link Env} saves the
     * land snapshot with both revisions at zero, and every authorisation
     * mutation below bumps the land revision exactly once, so pins are
     * derived by counting instead of crossing the persistence-thread
     * boundary from this package.
     */

    @Test
    void matchingExpectedCommitsPublishesAndEmitsOnce() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setDefaultExpected(env.owner, env.land,
                    ProtectionActionType.BLOCK_BREAK, PermissionState.INHERIT, 0L,
                    PermissionState.DENY).toCompletableFuture().join();

            assertEquals(1, env.calls.get());
            PermissionChangedEvent event = env.events.get(0);
            assertEquals(ChangeKind.DEFAULT_SET, event.kind());
            assertEquals(ProtectionActionType.BLOCK_BREAK, event.action());
            assertEquals(PermissionState.DENY, event.state());
            assertEquals(PermissionState.DENY, env.service.cache().snapshot()
                    .landDefault(env.land, ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void staleExpectedFailsWithoutWritingPublishingOrEmitting() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setDefault(env.owner, env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY).toCompletableFuture().join();
            assertEquals(1, env.calls.get());

            try {
                env.service.setDefaultExpected(env.owner, env.land,
                        ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW, 1L,
                        PermissionState.DENY).toCompletableFuture().join();
                fail("stale expected must fail");
            } catch (CompletionException conflict) {
                assertTrue(conflict.getCause() instanceof LandDefaultConflictException,
                        "stale expected must surface the conflict, got: " + conflict.getCause());
            }

            assertEquals(1, env.calls.get(), "a refused write must not emit");
            assertEquals(PermissionState.DENY, env.service.cache().snapshot()
                    .landDefault(env.land, ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void staleRevisionFailsWithoutWritingPublishingOrEmitting() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setDefault(env.owner, env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY).toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            env.service.trust(env.owner, env.land, UUID.randomUUID())
                    .toCompletableFuture().join();

            try {
                env.service.setDefaultExpected(env.owner, env.land,
                        ProtectionActionType.BLOCK_BREAK, PermissionState.DENY, 1L,
                        PermissionState.ALLOW).toCompletableFuture().join();
                fail("a lapsed authorisation must fail");
            } catch (CompletionException refused) {
                assertTrue(refused.getCause()
                        instanceof com.smile.chunkland.persistence.StaleAuthorisationException,
                        "a lapsed authorisation must surface the authorisation error, got: "
                                + refused.getCause());
            }

            assertEquals(2, env.calls.get(),
                    "only the two committed writes may emit, not the refused one");
            assertEquals(PermissionState.DENY, env.service.cache().snapshot()
                    .landDefault(env.land, ProtectionActionType.BLOCK_BREAK));
        }
    }

    @Test
    void unconditionalSetDefaultKeepsItsOriginalSemantics() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setDefault(env.owner, env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.ALLOW).toCompletableFuture().join();
            env.service.setDefault(env.owner, env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY).toCompletableFuture().join();

            assertEquals(2, env.calls.get());
            assertEquals(PermissionState.DENY, env.service.cache().snapshot()
                    .landDefault(env.land, ProtectionActionType.BLOCK_BREAK));
        }
    }
}
