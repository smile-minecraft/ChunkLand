package com.smile.chunkland.wand;

import com.smile.chunkland.command.ReplySink;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Handler for /land wand.
 */
public class WandGiveHandler implements com.smile.chunkland.command.LandCommand.Handler {

    protected ItemStack createWand() {
        return WandFactory.createWand();
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
