package com.smile.chunkland.wand;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class FakeItemStack extends ItemStack {
    private ItemMeta meta;

    public FakeItemStack(ItemMeta meta) {
        // Use super no-arg to avoid Material requirement; then override behavior
        super();
        this.meta = meta;
    }

    @Override
    public ItemMeta getItemMeta() {
        return meta;
    }

    @Override
    public boolean setItemMeta(ItemMeta m) {
        this.meta = m;
        return true;
    }

    @Override
    public boolean hasItemMeta() {
        return meta != null;
    }

    // Avoid Material registry by overriding getType to return null
    @Override
    public org.bukkit.Material getType() {
        return null;
    }
}
