package com.smile.chunkland.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.LandCreatePostEvent;
import com.smile.chunkland.api.event.LandCreatePreEvent;
import com.smile.chunkland.api.event.LandDeletePostEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.event.bukkit.LandCreatePostBukkitEvent;
import com.smile.chunkland.event.bukkit.LandCreatePreBukkitEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

/**
 * Facade over the API bus plus the Bukkit bridge: merged cancellation,
 * fail-closed Bukkit failures, Post isolation, and the no-op default.
 */
class PublicEventsTest {

    private static LandCreatePreEvent pre() {
        UUID world = UUID.randomUUID();
        return new LandCreatePreEvent(UUID.randomUUID(), world,
                OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, 0, 0)), "Home");
    }

    private static LandCreatePreBukkitEvent bukkitPre(LandCreatePreEvent event) {
        return new LandCreatePreBukkitEvent(event.actorUuid(), event.worldId(), event.owner(),
                event.chunks(), event.displayName(), false);
    }

    @Test
    void apiVetoCancelsWithoutTouchingBukkit() {
        List<Event> bukkitCalls = new ArrayList<>();
        PublicEvents events = PublicEvents.create(new PublicEventBus(), bukkitCalls::add);
        events.bus().register(LandCreatePreEvent.class, event -> event.setCancelled(true));
        LandCreatePreEvent event = pre();
        assertTrue(events.firePre(event, () -> bukkitPre(event)));
        assertEquals(1, bukkitCalls.size());
        assertTrue(event.isCancelled());
    }

    @Test
    void bukkitVetoCancelsApiEvent() {
        PublicEvents events = PublicEvents.create(new PublicEventBus(),
                event -> ((LandCreatePreBukkitEvent) event).setCancelled(true));
        LandCreatePreEvent event = pre();
        assertTrue(events.firePre(event, () -> bukkitPre(event)));
        assertTrue(event.isCancelled());
    }

    @Test
    void throwingApiListenerFailsClosed() {
        List<Event> bukkitCalls = new ArrayList<>();
        PublicEvents events = PublicEvents.create(new PublicEventBus(), bukkitCalls::add);
        events.bus().register(LandCreatePreEvent.class, event -> {
            throw new IllegalStateException("broken api listener");
        });
        LandCreatePreEvent event = pre();
        assertTrue(events.firePre(event, () -> bukkitPre(event)));
        assertTrue(event.isCancelled());
    }

    @Test
    void throwingBukkitCallerFailsClosed() {
        PublicEvents events = PublicEvents.create(new PublicEventBus(), event -> {
            throw new IllegalStateException("bridge down");
        });
        LandCreatePreEvent event = pre();
        assertTrue(events.firePre(event, () -> bukkitPre(event)));
        assertTrue(event.isCancelled());
    }

    @Test
    void throwingBukkitViewFactoryFailsClosed() {
        PublicEvents events = PublicEvents.create(new PublicEventBus(), event -> {
        });
        LandCreatePreEvent event = pre();
        assertTrue(events.firePre(event, () -> {
            throw new IllegalStateException("view failed");
        }));
        assertTrue(event.isCancelled());
    }

    @Test
    void cleanPrePassesThrough() {
        AtomicInteger apiCalls = new AtomicInteger();
        List<Event> bukkitCalls = new ArrayList<>();
        PublicEvents events = PublicEvents.create(new PublicEventBus(), bukkitCalls::add);
        events.bus().register(LandCreatePreEvent.class, event -> apiCalls.incrementAndGet());
        LandCreatePreEvent event = pre();
        assertFalse(events.firePre(event, () -> bukkitPre(event)));
        assertFalse(event.isCancelled());
        assertEquals(1, apiCalls.get());
        assertEquals(1, bukkitCalls.size());
    }

    @Test
    void postIsolationSurvivesThrowingApiAndBukkitListeners() {
        PublicEvents events = PublicEvents.create(new PublicEventBus(), event -> {
            throw new IllegalStateException("bridge down");
        });
        events.bus().register(LandCreatePostEvent.class, event -> {
            throw new IllegalStateException("broken api listener");
        });
        events.firePost(
                new LandCreatePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(),
                        UUID.randomUUID(), 1, 10L),
                () -> new LandCreatePostBukkitEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(),
                        UUID.randomUUID(), 1, 10L, true));
    }

    @Test
    void postStillReachesBukkitWhenApiListenerThrows() {
        List<Event> bukkitCalls = new ArrayList<>();
        PublicEvents events = PublicEvents.create(new PublicEventBus(), bukkitCalls::add);
        events.bus().register(LandDeletePostEvent.class, event -> {
            throw new IllegalStateException("broken api listener");
        });
        events.firePost(new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L),
                () -> new com.smile.chunkland.event.bukkit.LandDeletePostBukkitEvent(
                        new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L, true));
        assertEquals(1, bukkitCalls.size());
    }

    @Test
    void noopFacadeNeverCancelsAndNeverThrows() {
        PublicEvents events = PublicEvents.noop();
        LandCreatePreEvent event = pre();
        assertFalse(events.firePre(event, () -> {
            throw new IllegalStateException("must never be built");
        }));
        assertFalse(event.isCancelled());
        events.firePost(
                new LandDeletePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(), 0L),
                () -> {
                    throw new IllegalStateException("must never be built");
                });
    }

    @Test
    void cancelledExceptionCarriesStableKey() {
        assertEquals("event.cancelled", PublicEventCancelledException.DIAGNOSTIC_KEY);
    }
}
