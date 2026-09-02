package com.smile.chunkland.wand;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Identity check for the ChunkLand selection wand.
 * Only the exact PDC contract defines identity.
 */
public final class WandIdentity {

    private WandIdentity() {}

    public static boolean isWand(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        ItemMeta meta;
        try {
            meta = stack.getItemMeta();
        } catch (RuntimeException ex) {
            throw ex;
        }
        return isWand(meta);
    }

    public static boolean isWand(ItemMeta meta) {
        if (meta == null) {
            return false;
        }
        var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(WandKeys.ITEM_TYPE, PersistentDataType.STRING)) {
            return false;
        }
        if (!pdc.has(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER)) {
            return false;
        }
        String type = pdc.get(WandKeys.ITEM_TYPE, PersistentDataType.STRING);
        Integer version = pdc.get(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER);
        if (type == null || version == null) {
            return false;
        }
        return WandKeys.ITEM_TYPE_VALUE.equals(type) && version == WandKeys.SCHEMA_VERSION_VALUE;
    }
}
