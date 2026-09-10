package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.ConfigLoader;
import com.smile.chunkland.config.ConfigReloadListener;
import com.smile.chunkland.config.ConfigSchema;
import com.smile.chunkland.config.ConfigService;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Disable must detach the permission defaults cache.
 *
 * <p>The cache is registered as a {@link ConfigReloadListener} at enable.
 * If cleanup leaves it registered and keeps the field, a retained
 * {@link ConfigService} that reloads after disable still refreshes the
 * disabled cache. Cleanup must remove the listener first, then clear the
 * field, and stay safe on repeated calls.
 */
class PermissionDefaultsCleanupLifecycleTest {

    private static final String DENY_YAML = ""
            + "subject-defaults:\n"
            + "  global:\n"
            + "    BLOCK_BREAK: DENY\n";

    private static final String ALLOW_YAML = ""
            + "subject-defaults:\n"
            + "  global:\n"
            + "    BLOCK_BREAK: ALLOW\n";

    private static final class MutableYamlLoader implements ConfigLoader {
        private volatile String yaml;

        MutableYamlLoader(String yaml) {
            this.yaml = yaml;
        }

        void setYaml(String yaml) {
            this.yaml = yaml;
        }

        @Override
        public ChunkLandConfig load() {
            return ConfigSchema.parseYamlText(yaml);
        }

        @Override
        public String describe() {
            return "mutable-yaml";
        }
    }

    @SuppressWarnings("unchecked")
    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == java.util.Optional.class && value == null) {
                unsafe.putObject(plugin, offset, java.util.Optional.empty());
            } else if (field.getType() == com.smile.chunkland.adapter.AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new com.smile.chunkland.adapter.AceLibBridge());
            }
        }
        return plugin;
    }

    @SuppressWarnings("unchecked")
    private static List<ConfigReloadListener> listenersOf(ConfigService service) throws Exception {
        Field field = ConfigService.class.getDeclaredField("listeners");
        field.setAccessible(true);
        CopyOnWriteArrayList<ConfigReloadListener> listeners =
                (CopyOnWriteArrayList<ConfigReloadListener>) field.get(service);
        return new ArrayList<>(listeners);
    }

    private static PermissionDefaultsCache defaultsFieldOf(ChunkLandPlugin plugin) throws Exception {
        Field field = ChunkLandPlugin.class.getDeclaredField("permissionDefaults");
        field.setAccessible(true);
        return (PermissionDefaultsCache) field.get(plugin);
    }

    @Test
    void cleanupDetachesDefaultsListenerAndFreezesDisabledCache() throws Exception {
        MutableYamlLoader loader = new MutableYamlLoader(DENY_YAML);
        ConfigService service = new ConfigService(loader);
        List<String> warnings = new ArrayList<>();
        PermissionDefaultsCache cache = ChunkLandPlugin.buildPermissionDefaults(
                service, Map.of(), warnings::add);
        // Enable wiring: the plugin registers the assembled cache.
        service.addListener(cache);

        ChunkLandPlugin plugin = allocatePlugin();
        Field configField = ChunkLandPlugin.class.getDeclaredField("configService");
        configField.setAccessible(true);
        configField.set(plugin, Optional.of(service));
        Field defaultsField = ChunkLandPlugin.class.getDeclaredField("permissionDefaults");
        defaultsField.setAccessible(true);
        defaultsField.set(plugin, cache);

        assertEquals(PermissionState.DENY,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK));
        assertTrue(listenersOf(service).contains(cache),
                "enable wiring must register the defaults cache");

        plugin.performFullCleanup();

        assertNull(defaultsFieldOf(plugin), "cleanup must clear the defaults field");
        assertFalse(listenersOf(service).contains(cache),
                "cleanup must remove the defaults listener before clearing the field");

        // A retained service reloading after disable must not refresh the old cache.
        PermissionState beforeReload =
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK);
        loader.setYaml(ALLOW_YAML);
        service.reload();
        assertEquals(beforeReload,
                cache.snapshot().subjectGlobalDefault(ProtectionActionType.BLOCK_BREAK),
                "post-disable reload must not touch the disabled cache");

        assertDoesNotThrow(plugin::performFullCleanup,
                "repeated cleanup must stay idempotent");
        assertNull(defaultsFieldOf(plugin), "repeated cleanup must keep the field clear");
    }
}
