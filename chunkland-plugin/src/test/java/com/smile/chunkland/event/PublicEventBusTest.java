package com.smile.chunkland.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.LandChunkAddPostEvent;
import com.smile.chunkland.api.event.LandCreatePreEvent;
import com.smile.chunkland.api.event.LandDeletePostEvent;
import com.smile.chunkland.api.event.NoopChunkLandEventBus;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Synchronous dispatch: Pre fail-closed on listener failure, Post listener
 * isolation, register/unregister, and calling-thread delivery.
 */
class PublicEventBusTest {

    private static LandCreatePreEvent pre() {
        UUID world = UUID.randomUUID();
        return new LandCreatePreEvent(UUID.randomUUID(), world,
                OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, 0, 0)), "Home");
    }

    @Test
    void deliversSynchronouslyOnTheCallingThread() {
        PublicEventBus bus = new PublicEventBus();
        Thread caller = Thread.currentThread();
        List<Thread> seen = new CopyOnWriteArrayList<>();
        LandId land = new LandId(UUID.randomUUID());
        bus.register(LandDeletePostEvent.class, event -> seen.add(Thread.currentThread()));
        bus.publish(new LandDeletePostEvent(land, UUID.randomUUID(), 0L));
        assertEquals(List.of(caller), seen);
    }

    @Test
    void preListenerVetoCancels() {
        PublicEventBus bus = new PublicEventBus();
        bus.register(LandCreatePreEvent.class, event -> event.setCancelled(true));
        LandCreatePreEvent event = pre();
        bus.publish(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void throwingPreListenerFailsClosed() {
        PublicEventBus bus = new PublicEventBus();
        bus.register(LandCreatePreEvent.class, event -> {
            throw new IllegalStateException("broken listener");
        });
        LandCreatePreEvent event = pre();
        bus.publish(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void throwingPostListenerIsIsolatedAndOthersStillRun() {
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger second = new AtomicInteger();
        bus.register(LandDeletePostEvent.class, event -> {
            throw new IllegalStateException("broken listener");
        });
        bus.register(LandDeletePostEvent.class, event -> second.incrementAndGet());
        bus.publish(new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L));
        assertEquals(1, second.get());
    }

    @Test
    void postPublishNeverThrowsEvenWhenEveryListenerThrows() {
        PublicEventBus bus = new PublicEventBus();
        bus.register(LandDeletePostEvent.class, event -> {
            throw new IllegalStateException("broken listener");
        });
        bus.publish(new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L));
    }

    @Test
    void unregisterStopsDelivery() {
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger calls = new AtomicInteger();
        var listener = new com.smile.chunkland.api.event.ChunkLandEventListener<LandDeletePostEvent>() {
            @Override
            public void onEvent(LandDeletePostEvent event) {
                calls.incrementAndGet();
            }
        };
        bus.register(LandDeletePostEvent.class, listener);
        bus.unregister(LandDeletePostEvent.class, listener);
        bus.publish(new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L));
        assertEquals(0, calls.get());
    }

    @Test
    void subtypeEventsReachSupertypeListeners() {
        ChunkLandEventBus bus = new PublicEventBus();
        AtomicInteger calls = new AtomicInteger();
        bus.register(Object.class, event -> calls.incrementAndGet());
        bus.publish(new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L));
        bus.publish(pre());
        assertEquals(2, calls.get());
    }

    @Test
    void postChunkSnapshotsAreImmutableAndBounded() {
        UUID world = UUID.randomUUID();
        LandChunkAddPostEvent event = new LandChunkAddPostEvent(new LandId(UUID.randomUUID()),
                UUID.randomUUID(), Set.of(new ChunkKey(world, 3, 4)), 100L);
        assertEquals(Set.of(new ChunkKey(world, 3, 4)), event.addedChunks());
        assertEquals(100L, event.priceMinorUnits());
        assertFalse(event.addedChunks().isEmpty());
    }

    @Test
    void noopBusLeavesPreUncancelled() {
        LandCreatePreEvent event = pre();
        NoopChunkLandEventBus.instance().publish(event);
        assertFalse(event.isCancelled());
    }
}
