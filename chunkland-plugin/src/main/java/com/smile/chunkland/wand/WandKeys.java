package com.smile.chunkland.wand;

import org.bukkit.NamespacedKey;

/**
 * Centralized PDC keys for the selection wand.
 */
public final class WandKeys {

    public static final String NAMESPACE = "chunkland";
    public static final String ITEM_TYPE_KEY = "item_type";
    public static final String SCHEMA_VERSION_KEY = "schema_version";

    public static final String ITEM_TYPE_VALUE = "selection_wand";
    public static final int SCHEMA_VERSION_VALUE = 1;

    public static final NamespacedKey ITEM_TYPE = new NamespacedKey(NAMESPACE, ITEM_TYPE_KEY);
    public static final NamespacedKey SCHEMA_VERSION = new NamespacedKey(NAMESPACE, SCHEMA_VERSION_KEY);

    private WandKeys() {}
}
