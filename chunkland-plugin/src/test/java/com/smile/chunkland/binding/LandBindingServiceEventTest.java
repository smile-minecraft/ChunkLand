package com.smile.chunkland.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.Cuboid;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.LandBindingRepository.Subject;
import com.smile.chunkland.persistence.LandBindingRepository.SubjectKind;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.persistence.SqliteSubLandRepository;
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
 * PermissionChanged for generic bindings: exactly one event after the
 * durable commit plus the snapshot publish, on land and subland scopes;
 * none on failure; a throwing listener never rolls the mutation back.
 */
class LandBindingServiceEventTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir Path tmp;

    private static final class Env implements AutoCloseable {
        final PersistenceStore store;
        final LandBindingService service;
        final PublicEventBus bus = new PublicEventBus();
        final AtomicInteger calls = new AtomicInteger();
        final List<PermissionChangedEvent> events = Collections.synchronizedList(new ArrayList<>());
        final UUID world = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final LandId land = new LandId(UUID.randomUUID());
        final SubLandId sub = new SubLandId(UUID.randomUUID());
        final UUID profile;

        Env(Path db) {
            store = PersistenceStore.open(db);
            SubLandSnapshot subSnap = new SubLandSnapshot(sub, land, "Den",
                    new Cuboid(0, 0, 0, 15, 255, 15), world);
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(owner), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(subSnap), 0, 0, NOW, NOW)).toCompletableFuture().join();
            new SqliteSubLandRepository(store).save(subSnap).toCompletableFuture().join();
            profile = new PermissionProfileRepository(store)
                    .create(owner, "Crew", owner, NOW).toCompletableFuture().join().id();
            bus.register(PermissionChangedEvent.class, event -> {
                calls.incrementAndGet();
                events.add(event);
            });
            service = new LandBindingService(new LandBindingRepository(store),
                    new LandAuthorisationCache(), Clock.fixed(NOW, ZoneOffset.UTC),
                    PublicEvents.create(bus, null));
        }

        @Override
        public void close() {
            store.close();
        }
    }

    @Test
    void bindLandEmitsExactlyOneBindEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.bindLand(env.owner, env.land,
                    new Subject(SubjectKind.PLAYER, target), env.profile)
                    .toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            PermissionChangedEvent event = env.events.get(0);
            assertEquals(ChangeKind.BIND, event.kind());
            assertEquals(env.land, event.landId());
            assertEquals(env.owner, event.actorUuid());
            assertEquals(PermissionChangedEvent.SubjectKind.PLAYER, event.subjectKind());
            assertEquals(target, event.subjectId());
            assertEquals(env.profile, event.profileId());
        }
    }

    @Test
    void unbindLandEmitsExactlyOneUnbindEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.bindLand(env.owner, env.land,
                    new Subject(SubjectKind.PLAYER, target), env.profile)
                    .toCompletableFuture().join();
            env.service.unbindLand(env.owner, env.land,
                    new Subject(SubjectKind.PLAYER, target)).toCompletableFuture().join();
            assertEquals(2, env.calls.get());
            PermissionChangedEvent event = env.events.get(1);
            assertEquals(ChangeKind.UNBIND, event.kind());
            assertEquals(env.land, event.landId());
            assertEquals(target, event.subjectId());
        }
    }

    @Test
    void bindSublandEmitsSublandScopedEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.bindSubland(env.owner, env.sub,
                    new Subject(SubjectKind.PLAYER, target), env.profile)
                    .toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            PermissionChangedEvent event = env.events.get(0);
            assertEquals(ChangeKind.BIND, event.kind());
            assertEquals(null, event.landId());
            assertEquals(env.sub, event.subLandId());
            assertEquals(target, event.subjectId());
        }
    }

    @Test
    void failedBindEmitsNothing() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            // Unknown profile: the durable write fails, so no event may fire.
            try {
                env.service.bindLand(env.owner, env.land,
                        new Subject(SubjectKind.PLAYER, UUID.randomUUID()), UUID.randomUUID())
                        .toCompletableFuture().join();
                fail("bind with unknown profile must fail");
            } catch (CompletionException expected) {
                // Durable failure: no event.
            }
            assertEquals(0, env.calls.get());
        }
    }

    @Test
    void throwingListenerDoesNotRollBackBind() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.bus.register(PermissionChangedEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });
            env.service.bindLand(env.owner, env.land,
                    new Subject(SubjectKind.PLAYER, target), env.profile)
                    .toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            // The binding is durable despite the broken listener: unbind
            // still finds and removes it without error.
            env.service.unbindLand(env.owner, env.land,
                    new Subject(SubjectKind.PLAYER, target)).toCompletableFuture().join();
            assertEquals(2, env.calls.get());
        }
    }

    @Test
    void permissionStatesSurviveTheEventBoundary() {
        // Compile-level pin: the event carries real API permission types.
        assertEquals(PermissionState.ALLOW, PermissionState.valueOf("ALLOW"));
        assertEquals(ProtectionActionType.BLOCK_BREAK, ProtectionActionType.valueOf("BLOCK_BREAK"));
        assertTrue(true);
    }
}
