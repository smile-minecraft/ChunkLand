package com.smile.chunkland.event;

import org.bukkit.event.Event;

/**
 * Seam over the server's event dispatch.
 *
 * <p>Production passes the plugin manager's {@code callEvent}; tests pass a
 * recording or vetoing fake. The call runs synchronously on the publishing
 * thread, so Pre calls happen before any Economy or durable side effect and
 * Post calls happen after the durable commit plus the runtime publish.
 * Implementations must never touch world state themselves; Bukkit listeners
 * behind the call must obey the no-blocking, no-I/O, Folia-thread rules
 * documented on the API events.
 */
@FunctionalInterface
public interface BukkitEventCaller {

    /** Dispatch one Bukkit view of a public event, synchronously. */
    void call(Event event);
}
