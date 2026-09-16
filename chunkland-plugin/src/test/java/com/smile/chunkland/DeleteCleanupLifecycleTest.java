package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.smile.chunkland.claim.DeleteSaga;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Disable must drop the whole-land delete saga.
 *
 * <p>Cleanup clears the field so a disabled generation keeps no runner that
 * could still mutate after the shared store closed, and repeats stay no-ops.
 */
class DeleteCleanupLifecycleTest {

    private static ChunkLandPlugin allocatePlugin() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        ChunkLandPlugin plugin = (ChunkLandPlugin) unsafe.allocateInstance(ChunkLandPlugin.class);
        for (var field : ChunkLandPlugin.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long offset = unsafe.objectFieldOffset(field);
            Object value = unsafe.getObject(plugin, offset);
            if (field.getType() == Optional.class && value == null) {
                unsafe.putObject(plugin, offset, Optional.empty());
            } else if (field.getType()
                    == com.smile.chunkland.adapter.AceLibBridge.class && value == null) {
                unsafe.putObject(plugin, offset, new com.smile.chunkland.adapter.AceLibBridge());
            }
        }
        return plugin;
    }

    private static Object fieldOf(ChunkLandPlugin plugin, String name) throws Exception {
        Field field = ChunkLandPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }

    @Test
    void cleanupClearsDeleteSagaAndStaysRepeatable() throws Exception {
        ChunkLandPlugin plugin = allocatePlugin();
        // A live saga handle without running its constructor: cleanup only has
        // to drop the reference.
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        Field sagaField = ChunkLandPlugin.class.getDeclaredField("deleteSaga");
        sagaField.setAccessible(true);
        sagaField.set(plugin, unsafe.allocateInstance(DeleteSaga.class));

        plugin.performFullCleanup();

        assertNull(fieldOf(plugin, "deleteSaga"), "cleanup must clear the delete saga field");
        assertDoesNotThrow(plugin::performFullCleanup, "repeated cleanup must stay idempotent");
        assertNull(fieldOf(plugin, "deleteSaga"));
    }
}
