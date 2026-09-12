package com.smile.chunkland.wand;

import com.smile.chunkland.selection.SelectionEndReason;
import com.smile.chunkland.selection.SelectionNotification;
import com.smile.chunkland.selection.SelectionNotifier;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Renders the selection end reasons the player should hear about.
 *
 * <p>Only the two reasons where the player is online and lost a live range get
 * a prompt: an explicit {@code CANCELLED} and the {@code ITEM_CHANGED} that
 * fires when the wand leaves the main hand. Timeout, quit, world change,
 * config reload, replacement, and plugin disable stay silent so the chat is
 * never spammed. The notifier holds no text — it maps a reason to a lang key
 * and lets the injected sender render it through the shared message pipeline.
 */
public final class WandSelectionNotifier implements SelectionNotifier {

    private final Function<UUID, Player> players;
    private final BiConsumer<Player, String> sender;

    public WandSelectionNotifier(Function<UUID, Player> players, BiConsumer<Player, String> sender) {
        this.players = Objects.requireNonNull(players, "players");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void notify(SelectionNotification notification) {
        if (notification == null) {
            return;
        }
        String key = messageKey(notification.reason());
        if (key == null) {
            return;
        }
        Player player;
        try {
            player = players.apply(notification.playerId());
        } catch (RuntimeException ex) {
            return;
        }
        if (player == null) {
            return;
        }
        try {
            sender.accept(player, key);
        } catch (RuntimeException ignored) {
            // A failed prompt must never break the cleanup path that fired it.
        }
    }

    /** @return the lang key for a reason the player is told about, else null to stay silent. */
    public static String messageKey(SelectionEndReason reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case CANCELLED -> "selection.wand.cancelled";
            case ITEM_CHANGED -> "selection.wand.abandoned";
            default -> null;
        };
    }
}
