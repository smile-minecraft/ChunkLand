package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.LandChunkRemovePostEvent;
import com.smile.chunkland.api.event.LandCreatePreEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.event.BukkitEventCaller;
import com.smile.chunkland.event.PublicEventBus;
import com.smile.chunkland.event.PublicEvents;
import com.smile.chunkland.event.bukkit.LandChunkRemovePostBukkitEvent;
import com.smile.chunkland.event.bukkit.LandCreatePreBukkitEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

/**
 * Production Bukkit bridge dispatch: the exact {@code BukkitEventCaller}
 * production builds from a {@code PluginManager} carries Post views with
 * the async flag set, dispatches synchronously on the publishing thread
 * (background for Post, caller thread for Pre), propagates Bukkit-side
 * vetoes, and isolates throwing Bukkit listeners without failing the
 * mutation outcome.
 *
 * <p>A fake {@code PluginManager} stands in for the server: it records the
 * dispatched view and invokes registered listener bodies directly. It
 * cannot model real Bukkit listener registration, but it executes the full
 * production bridge path ({@code PluginManager#callEvent} via the
 * production helper) rather than only the in-process bus.
 */
class PublicEventBridgeTest {

    private static final class FakeManager {
        final List<Event> dispatched = new CopyOnWriteArrayList<>();
        final List<Thread> dispatchThreads = new CopyOnWriteArrayList<>();
        final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
        final List<Throwable> listenerFailures = new CopyOnWriteArrayList<>();

        final PluginManager manager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[] {PluginManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("callEvent") && args != null && args.length == 1) {
                        Event event = (Event) args[0];
                        dispatched.add(event);
                        dispatchThreads.add(Thread.currentThread());
                        // Mirror SimplePluginManager: one throwing listener
                        // is logged and skipped while the rest still run.
                        for (Consumer<Event> listener : listeners) {
                            try {
                                listener.accept(event);
                            } catch (Throwable failure) {
                                listenerFailures.add(failure);
                            }
                        }
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static LandChunkRemovePostEvent shrinkPost() {
        UUID world = UUID.randomUUID();
        return new LandChunkRemovePostEvent(new LandId(UUID.randomUUID()), UUID.randomUUID(),
                Set.of(new ChunkKey(world, 3, 4)), 50L);
    }

    private static LandChunkRemovePostBukkitEvent shrinkPostView(LandChunkRemovePostEvent post) {
        return new LandChunkRemovePostBukkitEvent(post.landId(), post.actorUuid(),
                post.removedChunks(), post.refundMinorUnits(), true);
    }

    private static LandCreatePreEvent claimPre() {
        UUID world = UUID.randomUUID();
        return new LandCreatePreEvent(UUID.randomUUID(), world,
                OwnerRef.player(UUID.randomUUID()), Set.of(new ChunkKey(world, 0, 0)), "Home");
    }

    @Test
    void postBridgeDispatchesAsyncOnPublisherThreadAndIsolatesThrowingListeners() throws Exception {
        FakeManager fake = new FakeManager();
        AtomicInteger goodRuns = new AtomicInteger();
        AtomicInteger throwingRuns = new AtomicInteger();
        fake.listeners.add(event -> goodRuns.incrementAndGet());
        fake.listeners.add(event -> {
            throwingRuns.incrementAndGet();
            throw new IllegalStateException("broken Bukkit listener");
        });
        fake.listeners.add(event -> goodRuns.incrementAndGet());

        BukkitEventCaller caller = ChunkLandPlugin.bridgeCaller(fake.manager);
        List<Object> apiSeen = Collections.synchronizedList(new ArrayList<>());
        PublicEventBus bus = new PublicEventBus();
        bus.register(LandChunkRemovePostEvent.class, apiSeen::add);
        PublicEvents events = PublicEvents.create(bus, caller);

        ExecutorService background = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "bridge-post-test");
            thread.setDaemon(true);
            return thread;
        });
        Thread testThread = Thread.currentThread();
        LandChunkRemovePostEvent post = shrinkPost();
        try {
            Future<?> done = background.submit(() -> events.firePost(post, () -> shrinkPostView(post)));
            done.get(10, TimeUnit.SECONDS);
        } finally {
            background.shutdownNow();
        }

        assertEquals(1, fake.dispatched.size());
        Event view = fake.dispatched.get(0);
        assertTrue(view.isAsynchronous(), "Post Bukkit views must carry the async flag");
        assertTrue(view instanceof LandChunkRemovePostBukkitEvent);
        assertFalse(fake.dispatchThreads.get(0) == testThread,
                "Post bridge dispatch must run on the publishing (background) thread");
        assertEquals(1, apiSeen.size(), "API bus still observes the Post");
        assertEquals(1, throwingRuns.get(), "throwing Bukkit listener ran");
        assertEquals(2, goodRuns.get(), "listeners around the failure still ran");
        assertEquals(1, fake.listenerFailures.size());
    }

    @Test
    void preBridgeDispatchesSynchronouslyAndPropagatesVeto() {
        FakeManager fake = new FakeManager();
        fake.listeners.add(event -> ((LandCreatePreBukkitEvent) event).setCancelled(true));

        BukkitEventCaller caller = ChunkLandPlugin.bridgeCaller(fake.manager);
        PublicEvents events = PublicEvents.create(new PublicEventBus(), caller);

        LandCreatePreEvent pre = claimPre();
        Thread testThread = Thread.currentThread();
        boolean vetoed = events.firePre(pre, () -> new LandCreatePreBukkitEvent(pre.actorUuid(),
                pre.worldId(), pre.owner(), pre.chunks(), pre.displayName(), false));

        assertTrue(vetoed, "Bukkit-side veto must cancel the mutation");
        assertTrue(pre.isCancelled());
        assertEquals(1, fake.dispatched.size());
        assertFalse(fake.dispatched.get(0).isAsynchronous(), "Pre views stay synchronous");
        assertTrue(fake.dispatchThreads.get(0) == testThread,
                "Pre bridge dispatch must run synchronously on the caller thread");
    }

    @Test
    void preBridgeListenerThrowIsIsolatedByManager() {
        // Two failure levels: a throwing Bukkit *listener* is isolated by the
        // manager itself (mirroring SimplePluginManager), so the mutation
        // proceeds; only a throwing bridge *caller* (bridge down) fails the
        // Pre closed — that level is covered by throwingBukkitCallerFailsClosed
        // in PublicEventsTest.
        FakeManager fake = new FakeManager();
        fake.listeners.add(event -> {
            throw new IllegalStateException("broken Bukkit listener");
        });

        BukkitEventCaller caller = ChunkLandPlugin.bridgeCaller(fake.manager);
        PublicEvents events = PublicEvents.create(new PublicEventBus(), caller);

        LandCreatePreEvent pre = claimPre();
        assertFalse(events.firePre(pre, () -> new LandCreatePreBukkitEvent(pre.actorUuid(),
                pre.worldId(), pre.owner(), pre.chunks(), pre.displayName(), false)));
        assertFalse(pre.isCancelled());
        assertEquals(1, fake.listenerFailures.size());
    }
}
