package com.smile.chunkland.message.rejection;

import com.smile.chunkland.command.PlayerScheduler;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Production {@link RejectionNotifier.Sender}: hops an already-built notice
 * to the player's region thread before sending it as an ActionBar.
 *
 * <p>Thread boundary: the caller runs on the event thread and never touches
 * the {@link Player} here; the send itself runs on the player's thread
 * through the injected {@link PlayerScheduler}. A throwing scheduler
 * (retired entity scheduler, departed player) or a send failure on the
 * player thread drops that notice fail-closed — never retried, never
 * leaked, and never thrown back into event dispatch.
 */
public final class PlayerRegionRejectionSender implements RejectionNotifier.Sender {

    private final PlayerScheduler scheduler;

    public PlayerRegionRejectionSender(PlayerScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void send(Player player, Component message) {
        if (player == null || message == null) {
            return;
        }
        try {
            scheduler.runForPlayer(player, () -> {
                try {
                    player.sendActionBar(message);
                } catch (RuntimeException ignored) {
                    // Player-thread send failures stay silent on the deny path.
                }
            });
        } catch (RuntimeException ignored) {
            // Scheduling failures (retired scheduler, departed player) drop
            // the notice fail-closed without touching enforcement.
        }
    }
}
