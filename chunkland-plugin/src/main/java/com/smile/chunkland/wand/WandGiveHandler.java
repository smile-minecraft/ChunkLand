package com.smile.chunkland.wand;

import com.smile.chunkland.command.ReplySink;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Handler for /land wand.
 *
 * <p>Idempotent: a player who already holds a wand — current or legacy —
 * keeps it and is told so instead of receiving a duplicate. Only a plain
 * stick or an empty slot leads to a fresh wand.
 */
public class WandGiveHandler implements com.smile.chunkland.command.LandCommand.Handler {

    protected ItemStack createWand() {
        return WandFactory.createWand();
    }

    private static boolean alreadyHoldsWand(org.bukkit.inventory.PlayerInventory inv) {
        ItemStack[] contents;
        try {
            contents = inv.getContents();
        } catch (RuntimeException ex) {
            // An unreadable inventory cannot prove absence; fall through to the
            // normal give path rather than denying a wand on backend failure.
            return false;
        }
        if (contents == null) {
            return false;
        }
        for (ItemStack existing : contents) {
            if (existing == null) {
                continue;
            }
            boolean isWand;
            try {
                isWand = WandIdentity.isWand(existing);
            } catch (RuntimeException ex) {
                // Fail closed against duplication: an item whose identity cannot
                // be read counts as a wand so it is kept, never doubled.
                return true;
            }
            if (isWand) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.wand.console", Map.of());
            return;
        }
        var inv = player.getInventory();
        if (inv == null) {
            sink.reply("command.land.wand.console", Map.of());
            return;
        }
        if (alreadyHoldsWand(inv)) {
            sink.reply("command.land.wand.already_have", Map.of());
            return;
        }
        // Explicit full-inventory handling: do not drop; check before creating wand
        if (inv.firstEmpty() == -1) {
            sink.reply("command.land.wand.inventory_full", Map.of());
            return;
        }
        ItemStack wand;
        try {
            wand = createWand();
        } catch (RuntimeException ex) {
            sink.reply("command.land.wand.error", Map.of());
            return;
        }
        var leftover = inv.addItem(wand);
        if (leftover != null && !leftover.isEmpty()) {
            sink.reply("command.land.wand.inventory_full", Map.of());
            return;
        }
        sink.reply("command.land.wand.given", Map.of());
    }
}
