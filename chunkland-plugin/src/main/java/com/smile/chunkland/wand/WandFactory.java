package com.smile.chunkland.wand;

import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Factory for the ChunkLand selection wand.
 * Uses AceItemFactory.create("chunkland") plus ItemSpec for the base,
 * then writes the exact ChunkLand PDC contract.
 */
public final class WandFactory {

    private WandFactory() {}

    public static ItemStack createWand() {
        AceItemFactory factory = AceItemFactory.create(WandKeys.NAMESPACE);
        var spec = AceItemFactory.ItemSpec.builder()
                .material(Material.STICK)
                .amount(1)
                .identity(new ItemIdentity(WandKeys.NAMESPACE, WandKeys.ITEM_TYPE_VALUE, 1, 0))
                .build();
        ItemStack stack = factory.create(spec);
        // Add Glint without affecting identity
        try {
            factory.withEnchantment(stack, Enchantment.UNBREAKING, 1);
        } catch (RuntimeException ignored) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                stack.setItemMeta(meta);
            }
        }
        configure(stack);
        return stack;
    }

    static void configure(ItemStack stack) {
        if (stack == null) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        configure(meta);
        stack.setItemMeta(meta);
    }

    static void configure(ItemMeta meta) {
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, WandKeys.SCHEMA_VERSION_VALUE);
    }
}
