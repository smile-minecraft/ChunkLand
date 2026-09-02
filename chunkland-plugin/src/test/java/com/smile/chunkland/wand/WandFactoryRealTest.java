package com.smile.chunkland.wand;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class WandFactoryRealTest {

    @Test
    void realFactoryReturnsStickWithGlintAndExactPdc() {
        ItemStack wand;
        try {
            wand = WandFactory.createWand();
        } catch (Throwable e) {
            String combined = collectChainText(e);
            boolean isKnownPaperApiOnlyLimitation =
                    combined.contains("No RegistryAccess implementation found") ||
                    combined.contains("RegistryAccessHolder") ||
                    combined.contains("RegistryAccess.registryAccess") ||
                    combined.contains("craftDelegate is null");
            if (isKnownPaperApiOnlyLimitation) {
                abort("Skipping: paper-api-only JVM without live Paper registry/CraftItemStack - live Folia probe proves STICK/Glint/PDC (see Required Return)");
            }
            throw e;
        }
        assertNotNull(wand, "wand must not be null");
        assertEquals(Material.STICK, wand.getType(), "must be STICK");
        assertEquals(1, wand.getAmount(), "amount must be 1");
        assertTrue(wand.containsEnchantment(Enchantment.UNBREAKING), "must have Glint via UNBREAKING");
        assertTrue(WandIdentity.isWand(wand), "must be recognized as wand");
        var meta = wand.getItemMeta();
        assertNotNull(meta);
        var pdc = meta.getPersistentDataContainer();
        assertEquals("selection_wand", pdc.get(WandKeys.ITEM_TYPE, PersistentDataType.STRING));
        assertEquals(1, pdc.get(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER));
        assertEquals("chunkland", WandKeys.ITEM_TYPE.getNamespace());
        assertEquals("chunkland", WandKeys.SCHEMA_VERSION.getNamespace());
        // Check required ChunkLand identity is exact; allow AceLib's extra _id/_version fields
        assertTrue(pdc.has(WandKeys.ITEM_TYPE, PersistentDataType.STRING));
        assertTrue(pdc.has(WandKeys.SCHEMA_VERSION, PersistentDataType.INTEGER));
        java.util.Set<String> forbiddenChunklandKeys = java.util.Set.of("selection", "player", "land", "mode", "point", "session", "auth", "authorization");
        for (var key : pdc.getKeys()) {
            String k = key.getKey();
            String ns = key.getNamespace();
            if (ns.equals("chunkland")) {
                boolean isRequired = k.equals("item_type") || k.equals("schema_version");
                boolean isAceLibExtra = k.equals("_id") || k.equals("_version") || k.equals("id") || k.equals("version") || k.startsWith("_");
                assertTrue(isRequired || isAceLibExtra, "unexpected chunkland PDC key: " + k);
                assertFalse(forbiddenChunklandKeys.contains(k), "forbidden ChunkLand tag must not be present: " + k);
            }
        }
        ItemStack second = WandFactory.createWand();
        assertNotSame(wand, second);
        // ItemMeta instances must be independent
        var meta2 = second.getItemMeta();
        assertNotNull(meta2);
        assertNotSame(meta, meta2);
    }

    private static String collectChainText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append(' ');
            sb.append(cur.toString()).append(' ');
        }
        return sb.toString();
    }
}
