package com.smile.chunkland;

import com.smile.chunkland.gui.GuiNavigator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Production bridge from Bukkit inventory clicks to the Java GUI navigator.
 *
 * <p>The upstream {@code GuiService} validates and cancels protected slots
 * inside its own internal listener, but it never calls back into ChunkLand —
 * without this bridge a click on a bound button (for example the
 * {@code /land manage} entry at top slot 11) would stay cancelled and never
 * reach its page action. This listener therefore runs with
 * {@code ignoreCancelled = false} so it still observes the upstream-cancelled
 * click, then hands the player UUID, the navigator's current generation and
 * the event's raw slot to {@link GuiNavigator#handleClick(UUID, long, int)}.
 *
 * <p>Read-only on the event thread: only the clicker UUID, the tracked
 * current generation and the raw slot are read. The listener never touches a
 * Bukkit inventory, never changes the event, and never throws onto the
 * server thread. Routing authority stays with the navigator — stale
 * generations, unbound slots, player-inventory slots and closed sessions all
 * fail closed there. Everything dispatched here already runs on the thread
 * that owns the clicked view, and the navigator itself performs no Bukkit
 * access and no scheduling, so no Folia hop is needed; page actions that
 * need region-scoped Bukkit work schedule through their own seam.
 */
public final class GuiClickListener implements Listener {

    private final Supplier<GuiNavigator> navigators;

    /**
     * @param navigators live navigator source, read on every click; a
     *     {@code null} source (or a {@code null} navigator) fails every click
     *     closed
     */
    public GuiClickListener(Supplier<GuiNavigator> navigators) {
        this.navigators = navigators == null ? () -> null : navigators;
    }

    /**
     * Routes one inventory click to the navigator. Never throws, never
     * modifies Bukkit state, never changes the event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            dispatch(event, navigators);
        } catch (RuntimeException ignored) {
            // A bridge miss must never break the inventory event.
        }
    }

    /**
     * Read-only dispatch shared by the event handler: resolve the clicker,
     * the tracked generation and the raw slot, then hand them to the
     * navigator. Any unreadable piece fails the click closed.
     */
    static void dispatch(InventoryClickEvent event, Supplier<GuiNavigator> navigators) {
        if (event == null || navigators == null) {
            return;
        }
        final HumanEntity who;
        try {
            who = event.getWhoClicked();
        } catch (RuntimeException unreadable) {
            return;
        }
        if (!(who instanceof Player)) {
            return;
        }
        final UUID playerUuid;
        try {
            playerUuid = who.getUniqueId();
        } catch (RuntimeException unreadable) {
            return;
        }
        if (playerUuid == null) {
            return;
        }
        final GuiNavigator navigator;
        try {
            navigator = navigators.get();
        } catch (RuntimeException unresolved) {
            return;
        }
        if (navigator == null) {
            return;
        }
        final Optional<Long> generation;
        try {
            generation = navigator.currentGeneration(playerUuid);
        } catch (RuntimeException unresolved) {
            return;
        }
        if (generation == null || generation.isEmpty()) {
            return;
        }
        final int rawSlot;
        try {
            rawSlot = event.getRawSlot();
        } catch (RuntimeException unreadable) {
            return;
        }
        try {
            navigator.handleClick(playerUuid, generation.orElseThrow(), rawSlot);
        } catch (RuntimeException ignored) {
            // Dispatch stays contained; the inventory event is untouched.
        }
    }
}
