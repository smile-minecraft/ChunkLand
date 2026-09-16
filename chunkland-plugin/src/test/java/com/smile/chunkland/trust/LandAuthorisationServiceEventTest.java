package com.smile.chunkland.trust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.PermissionChangedEvent.ChangeKind;
import com.smile.chunkland.api.event.PermissionChangedEvent.SubjectKind;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
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
 * PermissionChanged for direct trust, defaults and bans: exactly one event
 * after the durable commit plus the snapshot publish; none on failure; a
 * throwing listener never rolls the mutation back.
 */
class LandAuthorisationServiceEventTest {

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

    @Test
    void trustEmitsExactlyOnePlayerEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.trust(env.owner, env.land, target).toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            PermissionChangedEvent event = env.events.get(0);
            assertEquals(ChangeKind.TRUST, event.kind());
            assertEquals(env.land, event.landId());
            assertEquals(env.owner, event.actorUuid());
            assertEquals(SubjectKind.PLAYER, event.subjectKind());
            assertEquals(target, event.subjectId());
        }
    }

    @Test
    void untrustEmitsExactlyOnePlayerEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.trust(env.owner, env.land, target).toCompletableFuture().join();
            env.service.untrust(env.owner, env.land, target).toCompletableFuture().join();
            assertEquals(2, env.calls.get());
            PermissionChangedEvent event = env.events.get(1);
            assertEquals(ChangeKind.UNTRUST, event.kind());
            assertEquals(target, event.subjectId());
        }
    }

    @Test
    void setDefaultEmitsActionScopedEvent() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            env.service.setDefault(env.owner, env.land, ProtectionActionType.BLOCK_BREAK,
                    PermissionState.DENY).toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            PermissionChangedEvent event = env.events.get(0);
            assertEquals(ChangeKind.DEFAULT_SET, event.kind());
            assertEquals(env.land, event.landId());
            assertEquals(ProtectionActionType.BLOCK_BREAK, event.action());
            assertEquals(PermissionState.DENY, event.state());
            assertEquals(null, event.subjectId());
        }
    }

    @Test
    void banAndUnbanEmitPlayerEvents() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.service.ban(env.owner, env.land, target).toCompletableFuture().join();
            env.service.unban(env.owner, env.land, target).toCompletableFuture().join();
            assertEquals(2, env.calls.get());
            assertEquals(ChangeKind.BAN, env.events.get(0).kind());
            assertEquals(ChangeKind.UNBAN, env.events.get(1).kind());
            assertEquals(target, env.events.get(0).subjectId());
        }
    }

    @Test
    void failedCommitEmitsNothing() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            // Unknown land: the durable write fails, so no event may fire.
            try {
                env.service.trust(env.owner, new LandId(UUID.randomUUID()), UUID.randomUUID())
                        .toCompletableFuture().join();
                fail("trust on unknown land must fail");
            } catch (CompletionException expected) {
                // Durable failure: no event.
            }
            assertEquals(0, env.calls.get());
        }
    }

    @Test
    void throwingListenerDoesNotRollBackTrust() {
        try (Env env = new Env(tmp.resolve(UUID.randomUUID() + ".db"))) {
            UUID target = UUID.randomUUID();
            env.bus.register(PermissionChangedEvent.class, event -> {
                throw new IllegalStateException("broken post listener");
            });
            env.service.trust(env.owner, env.land, target).toCompletableFuture().join();
            assertEquals(1, env.calls.get());
            // The binding is durable despite the broken listener: untrust
            // still finds and removes it without error.
            env.service.untrust(env.owner, env.land, target).toCompletableFuture().join();
            assertEquals(2, env.calls.get());
        }
    }
}
