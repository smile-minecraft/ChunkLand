package com.smile.chunkland.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
}
