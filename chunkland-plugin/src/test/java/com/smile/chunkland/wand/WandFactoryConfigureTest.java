package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/**
 * The selection wand must carry a clear custom identity: a display name and
 * lore that stay readable without any resource pack, a custom model value for
 * packs that restyle it, and the exact PDC contract the identity check owns.
 * A plain stick (no PDC, no cosmetics) must never pass as a wand.
 */
class WandFactoryConfigureTest {

    @Test
    void configureSetsDisplayNameLoreAndCustomModel() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);

        assertTrue(meta.hasDisplayName(), "wand must have a custom display name");
        assertNotNull(meta.displayName(), "display name must be readable without a resource pack");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(meta.displayName());
        assertFalse(plain.isBlank(), "display name must not be blank");
        assertTrue(plain.contains("ChunkLand"), "display name must identify ChunkLand: " + plain);

        assertTrue(meta.hasLore(), "wand must describe its use");
        assertNotNull(meta.lore());
        assertFalse(meta.lore().isEmpty(), "lore must not be empty");
        var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        boolean explainsUse = meta.lore().stream()
                .map(serializer::serialize)
                .anyMatch(line -> !line.isBlank());
        assertTrue(explainsUse, "at least one lore line must be readable");

        assertTrue(meta.hasCustomModelData(), "wand must carry a custom model value");
        assertEquals(WandKeys.CUSTOM_MODEL_DATA_VALUE, meta.getCustomModelData());
    }

    @Test
    void configureWritesExactPdcContract() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);

        assertEquals(WandKeys.ITEM_TYPE_VALUE, pdc.get(WandKeys.ITEM_TYPE, PersistentDataType.STRING));
        assertEquals(WandKeys.SCHEMA_VERSION_VALUE,
                pdc.get(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER));
        assertEquals(WandKeys.VARIANT_VALUE, pdc.get(WandKeys.VARIANT, PersistentDataType.STRING));
        assertEquals(WandKeys.CUSTOM_MODEL_DATA_VALUE,
                pdc.get(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER));
        assertTrue(WandIdentity.isWand(meta));
    }

    @Test
    void plainStickHasNoIdentity() {
        ItemMeta meta = TestWandHelpers.fakeMeta(TestWandHelpers.fakePdc());

        assertFalse(meta.hasDisplayName());
        assertFalse(meta.hasLore());
        assertFalse(meta.hasCustomModelData());
        assertFalse(WandIdentity.isWand(meta));
    }

    @Test
    void configureIsNullSafe() {
        assertDoesNotThrow(() -> WandFactory.configure((ItemMeta) null));
        assertDoesNotThrow(() -> WandFactory.configure((org.bukkit.inventory.ItemStack) null));
    }
}
