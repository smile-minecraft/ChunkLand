package com.smile.chunkland.wand;

import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
        pdc.set(WandKeys.VARIANT, PersistentDataType.STRING, WandKeys.VARIANT_VALUE);
        pdc.set(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER, WandKeys.CUSTOM_MODEL_DATA_VALUE);
        // Cosmetics stay readable without a resource pack; a cosmetic failure
        // must never break the identity above, so each is best-effort.
        try {
            meta.setCustomModelData(WandKeys.CUSTOM_MODEL_DATA_VALUE);
        } catch (RuntimeException ignored) {
        }
        try {
            meta.displayName(Component.text("ChunkLand Selection Wand · 選區魔杖", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        } catch (RuntimeException ignored) {
        }
        try {
            meta.lore(List.of(
                    Component.text("Click blocks with either button to set corners A, then B",
                            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("左鍵或右鍵點擊方塊，依序設定 A、B 兩點", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        } catch (RuntimeException ignored) {
        }
    }
}
