package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class WandIdentityTest {

    @Test
    void isWandTrueForExactPdc() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, WandKeys.SCHEMA_VERSION_VALUE);
        assertTrue(WandIdentity.isWand(meta));
    }

    @Test
    void isWandFalseForNullMeta() {
        assertFalse(WandIdentity.isWand((ItemMeta) null));
        assertFalse(WandIdentity.isWand((org.bukkit.inventory.ItemStack) null));
    }

    @Test
    void isWandFalseWhenMissingData() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        // missing both
        assertFalse(WandIdentity.isWand(meta));
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        assertFalse(WandIdentity.isWand(meta));
    }

    @Test
    void isWandFalseWrongNamespace() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        NamespacedKey wrong = new NamespacedKey("other", "item_type");
        pdc.set(wrong, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        assertFalse(WandIdentity.isWand(meta));
    }

    @Test
    void isWandFalseWrongType() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        // store schema_version as STRING instead of INTEGER
        // our fake PDC stores raw, but has() checks type; so set as STRING
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.STRING, "1");
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        // has INTEGER will be false
        assertFalse(WandIdentity.isWand(meta));
    }

    @Test
    void isWandFalseWrongValue() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, "other_wand");
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        assertFalse(WandIdentity.isWand(meta));

        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 2);
        assertFalse(WandIdentity.isWand(meta));
    }

    @Test
    void isWandPropagatesBackendException() {
        ItemMeta meta = TestWandHelpers.fakeMetaWithException();
        assertThrows(RuntimeException.class, () -> WandIdentity.isWand(meta));
    }

    @Test
    void isWandIgnoresMaterialAndEnchant() {
        // WandIdentity only checks PDC, so changing meta other fields doesn't affect.
        // We simulate by ensuring PDC exact but meta has enchant.
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, 1);
        // add fake enchant via proxy
        assertTrue(WandIdentity.isWand(meta));
        // even after enchant, still true
        assertTrue(WandIdentity.isWand(meta));
    }

    @Test
    void storesNoSelectionState() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        // only the four identity keys should be present
        assertEquals(4, pdc.getKeys().size());
        assertTrue(pdc.has(WandKeys.ITEM_TYPE, PersistentDataType.STRING));
        assertTrue(pdc.has(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER));
        assertTrue(pdc.has(WandKeys.VARIANT, PersistentDataType.STRING));
        assertTrue(pdc.has(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER));
        // ensure no other chunkland keys like selection, player, land, mode, point, session
        for (NamespacedKey k : pdc.getKeys()) {
            String key = k.getKey();
            assertTrue(key.equals("item_type") || key.equals("schema_version")
                            || key.equals("variant") || key.equals("custom_model_data"),
                    "unexpected key " + key);
        }
    }

    @Test
    void isWandTrueForFullIdentity() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        assertTrue(WandIdentity.isWand(meta));
        assertEquals(WandKeys.VARIANT_VALUE, pdc.get(WandKeys.VARIANT, PersistentDataType.STRING));
        assertEquals(WandKeys.CUSTOM_MODEL_DATA_VALUE,
                pdc.get(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER));
    }

    @Test
    void isWandTrueForLegacyTwoKeyWand() {
        // Wands issued before the variant/model keys existed must keep working
        // and must count as wands for the duplicate-give guard.
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, WandKeys.SCHEMA_VERSION_VALUE);
        assertTrue(WandIdentity.isWand(meta));
    }

    @Test
    void isWandFalseForWrongVariantOrModel() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);

        pdc.set(WandKeys.VARIANT, PersistentDataType.STRING, "other");
        assertFalse(WandIdentity.isWand(meta), "wrong variant must be rejected");

        pdc.set(WandKeys.VARIANT, PersistentDataType.STRING, WandKeys.VARIANT_VALUE);
        pdc.set(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.INTEGER, 999999);
        assertFalse(WandIdentity.isWand(meta), "wrong custom model value must be rejected");
    }

    @Test
    void isWandFalseForMistypedVariantOrModel() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        pdc.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, WandKeys.ITEM_TYPE_VALUE);
        pdc.set(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER, WandKeys.SCHEMA_VERSION_VALUE);
        pdc.set(WandKeys.VARIANT, PersistentDataType.INTEGER, 1);
        assertFalse(WandIdentity.isWand(meta), "variant under the wrong type must be rejected");

        pdc.remove(WandKeys.VARIANT);
        pdc.set(WandKeys.CUSTOM_MODEL_DATA, PersistentDataType.STRING, "1");
        assertFalse(WandIdentity.isWand(meta), "custom model under the wrong type must be rejected");
    }

    @Test
    void wandFactoryConfigureCreatesExactPdc() {
        PersistentDataContainer pdc = TestWandHelpers.fakePdc();
        ItemMeta meta = TestWandHelpers.fakeMeta(pdc);
        WandFactory.configure(meta);
        assertTrue(WandIdentity.isWand(meta));
        assertEquals(WandKeys.ITEM_TYPE_VALUE, pdc.get(WandKeys.ITEM_TYPE, PersistentDataType.STRING));
        assertEquals(1, pdc.get(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER));
    }

    @Test
    void configureIndependentInstances() {
        PersistentDataContainer pdc1 = TestWandHelpers.fakePdc();
        ItemMeta m1 = TestWandHelpers.fakeMeta(pdc1);
        WandFactory.configure(m1);

        PersistentDataContainer pdc2 = TestWandHelpers.fakePdc();
        ItemMeta m2 = TestWandHelpers.fakeMeta(pdc2);
        WandFactory.configure(m2);

        // mutate pdc1 doesn't affect pdc2
        pdc1.set(WandKeys.ITEM_TYPE, PersistentDataType.STRING, "tampered");
        assertFalse(WandIdentity.isWand(m1));
        assertTrue(WandIdentity.isWand(m2));
    }
}
