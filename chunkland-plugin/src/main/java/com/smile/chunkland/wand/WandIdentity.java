package com.smile.chunkland.wand;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Identity check for the ChunkLand selection wand.
 * Only the exact PDC contract defines identity: the item type and schema
 * version must match exactly, and the variant and custom model keys — when
 * present — must also match exactly. Wands issued before those keys existed
 * carry only the first two keys and still count, so earlier holders keep
 * working and are not issued a duplicate.
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
        if (!WandKeys.ITEM_TYPE_VALUE.equals(type) || version != WandKeys.SCHEMA_VERSION_VALUE) {
            return false;
        }
        if (pdc.has(WandKeys.VARIANT, PersistentDataType.STRING)) {
            if (!WandKeys.VARIANT_VALUE.equals(pdc.get(WandKeys.VARIANT, PersistentDataType.STRING))) {
                return false;
            }
        } else if (pdc.getKeys().contains(WandKeys.VARIANT)) {
            // Present under the wrong type: not a wand we issued.
            return false;
        }
        if (pdc.has(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER)) {
            if (!Integer.valueOf(WandKeys.CUSTOM_MODEL_DATA_VALUE)
                    .equals(pdc.get(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER))) {
                return false;
            }
        } else if (pdc.getKeys().contains(WandKeys.CUSTOM_MODEL_DATA)) {
            // Present under the wrong type: not a wand we issued.
            return false;
        }
        return true;
    }
}
