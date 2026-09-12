package com.smile.chunkland.wand;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class TestWandHelpers {

    private TestWandHelpers() {}

    public static PersistentDataContainer fakePdc() {
        Map<NamespacedKey, Object> store = new HashMap<>();
        return (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class[]{PersistentDataContainer.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    switch (n) {
                        case "set": {
                            NamespacedKey k = (NamespacedKey) args[0];
                            // second arg is type, third is value
                            store.put(k, args[2]);
                            return null;
                        }
                        case "has": {
                            NamespacedKey k = (NamespacedKey) args[0];
                            if (args.length == 2 && args[1] instanceof PersistentDataType type) {
                                Object v = store.get(k);
                                if (v == null) return false;
                                // check type matches stored type
                                if (type.equals(PersistentDataType.STRING)) return v instanceof String;
                                if (type.equals(PersistentDataType.INTEGER)) return v instanceof Integer;
                                return true;
                            }
                            if (args.length == 1) return store.containsKey((NamespacedKey) args[0]);
                            return false;
                        }
                        case "get": {
                            NamespacedKey k = (NamespacedKey) args[0];
                            PersistentDataType<?, ?> type = (PersistentDataType<?, ?>) args[1];
                            Object v = store.get(k);
                            if (v == null) return null;
                            if (type.equals(PersistentDataType.STRING) && v instanceof String) return v;
                            if (type.equals(PersistentDataType.INTEGER) && v instanceof Integer) return v;
                            return null;
                        }
                        case "remove": {
                            store.remove((NamespacedKey) args[0]);
                            return null;
                        }
                        case "getKeys": return Set.copyOf(store.keySet());
                        case "isEmpty": return store.isEmpty();
                        case "getSize": return store.size();
                        default: {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == void.class) return null;
                            return null;
                        }
                    }
                });
    }

    public static ItemMeta fakeMeta(PersistentDataContainer pdc) {
        Map<String, Object> extras = new HashMap<>();
        return (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(),
                new Class[]{ItemMeta.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    if (n.equals("getPersistentDataContainer")) return pdc;
                    if (n.equals("addEnchant")) {
                        // store enchant for inspection
                        if (args != null && args.length >= 2) extras.put("enchant:" + args[0], args[1]);
                        return true;
                    }
                    if (n.equals("hasEnchant")) return extras.containsKey("enchant:" + args[0]);
                    if (n.equals("getEnchants")) return Map.of();
                    if (n.equals("displayName") && (args == null || args.length == 0)) {
                        return extras.get("displayName");
                    }
                    if (n.equals("displayName") && args != null && args.length == 1) {
                        if (args[0] == null) extras.remove("displayName");
                        else extras.put("displayName", args[0]);
                        return null;
                    }
                    if (n.equals("hasDisplayName")) return extras.containsKey("displayName");
                    if (n.equals("lore") && (args == null || args.length == 0)) {
                        Object lore = extras.get("lore");
                        if (lore == null) return null;
                        @SuppressWarnings("unchecked")
                        java.util.List<net.kyori.adventure.text.Component> copy =
                                new java.util.ArrayList<>((java.util.List<net.kyori.adventure.text.Component>) lore);
                        return copy;
                    }
                    if (n.equals("lore") && args != null && args.length == 1) {
                        if (args[0] == null) extras.remove("lore");
                        else extras.put("lore", args[0]);
                        return null;
                    }
                    if (n.equals("hasLore")) return extras.containsKey("lore");
                    if (n.equals("hasCustomModelData")) return extras.containsKey("customModelData");
                    if (n.equals("getCustomModelData")) {
                        Object value = extras.get("customModelData");
                        return value == null ? 0 : value;
                    }
                    if (n.equals("setCustomModelData")) {
                        if (args == null || args[0] == null) extras.remove("customModelData");
                        else extras.put("customModelData", args[0]);
                        return null;
                    }
                    if (n.equals("clone")) return proxy;
                    if (n.equals("equals")) return proxy == args[0];
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("toString")) return "FakeMeta";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == void.class) return null;
                    return null;
                });
    }

    public static ItemMeta fakeMetaWithException() {
        return (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(),
                new Class[]{ItemMeta.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPersistentDataContainer")) {
                        throw new RuntimeException("PDC failure");
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    return null;
                });
    }
}
