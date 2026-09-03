package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

/**
 * Guards native registration: every {@code @EventHandler} parameter must be a
 * concrete event with its own handler list. Abstract bases such as
 * {@code PlayerBucketEvent} have none, and Bukkit walks up to
 * {@code PlayerEvent} before failing at enable time.
 */
class ProtectionListenerRegistrationTest {

    @Test
    void everyHandlerEventHasItsOwnHandlerList() {
        List<String> withoutHandlerList = new ArrayList<>();
        for (Method method : ProtectionListener.class.getDeclaredMethods()) {
            if (method.getAnnotation(EventHandler.class) == null) {
                continue;
            }
            Class<?> eventType = method.getParameterTypes()[0];
            if (!hasStaticHandlerList(eventType)) {
                withoutHandlerList.add(method.getName() + "(" + eventType.getName() + ")");
            }
        }
        assertTrue(withoutHandlerList.isEmpty(),
                "handlers must only listen to concrete events with a handler list, missing: "
                        + withoutHandlerList);
    }

    private static boolean hasStaticHandlerList(Class<?> eventType) {
        // Mirrors Bukkit registration: it walks up the event hierarchy looking
        // for a static getHandlerList. A subclass without its own list still
        // registers through its parent (BlockBreakEvent via BlockExpEvent,
        // EntityDamageByEntityEvent via EntityDamageEvent); only a chain with
        // no list at all (PlayerBucketEvent -> PlayerEvent -> Event) fails.
        for (Class<?> current = eventType;
                current != null && org.bukkit.event.Event.class.isAssignableFrom(current);
                current = current.getSuperclass()) {
            try {
                Method handlerList = current.getDeclaredMethod("getHandlerList");
                if (Modifier.isStatic(handlerList.getModifiers())
                        && HandlerList.class.isAssignableFrom(handlerList.getReturnType())) {
                    return true;
                }
            } catch (NoSuchMethodException ignored) {
                // Keep walking up the hierarchy.
            }
        }
        return false;
    }
}
