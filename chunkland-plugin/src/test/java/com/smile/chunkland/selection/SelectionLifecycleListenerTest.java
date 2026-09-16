package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class SelectionLifecycleListenerTest {
    @Test
    void BukkitAdapterDeclaresQuitWorldChangeAndRespawnHooksAtMonitor() {
        Set<String> handlerNames = java.util.Arrays.stream(SelectionLifecycleListener.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(EventHandler.class) != null)
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("onPlayerQuit", "onPlayerChangedWorld", "onPlayerRespawn"), handlerNames);
        for (Method method : SelectionLifecycleListener.class.getDeclaredMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation != null) {
                assertEquals(EventPriority.MONITOR, annotation.priority());
                assertNotNull(method.getParameterTypes()[0]);
            }
        }
    }

    @Test
    void quitAndWorldChangeStopTheOccupiedPreview() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class[] {Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId" -> { return playerId; }
                        case "getName" -> { return "TestPlayer"; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            return null;
                        }
                    }
                });
        SelectionSessionManager manager = new SelectionSessionManager(
                (id, delay, task) -> SelectionTimeoutScheduler.Cancellable.noop(),
                SelectionVisualizationTaskController.noop(),
                notification -> { },
                () -> Instant.parse("2026-09-01T00:00:00Z"),
                () -> Duration.ofMinutes(10),
                uuid -> Optional.empty(),
                SelectionStructureRevisionLookup.unavailable());
        RecordingPreview preview = new RecordingPreview();
        SelectionLifecycleListener listener = new SelectionLifecycleListener(manager, preview);

        listener.onPlayerQuit(new PlayerQuitEvent(player, "quit"));
        listener.onPlayerChangedWorld(new PlayerChangedWorldEvent(player, fakeWorld()));

        assertEquals(List.of(playerId, playerId), preview.stopped,
                "quit and world change must each stop the occupied preview so no orphan render survives");
        assertTrue(manager.sessionFor(playerId).isEmpty());
    }

    private static World fakeWorld() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[] {World.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName" -> { return "world"; }
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            return null;
                        }
                    }
                });
    }

    private static final class RecordingPreview implements OccupiedPreviewController {
        final List<UUID> stopped = new ArrayList<>();

        @Override
        public void show(UUID playerId, java.util.Set<com.smile.chunkland.api.land.ChunkKey> chunks, double planeY) {
        }

        @Override
        public void stop(UUID playerId) {
            stopped.add(playerId);
        }
    }
}
