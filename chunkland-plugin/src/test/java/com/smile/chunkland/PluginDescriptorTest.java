package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PluginDescriptorTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDescriptor() {
        try (InputStream in = getClass().getResourceAsStream("/plugin.yml")) {
            if (in == null) {
                throw new IllegalStateException("plugin.yml not found on classpath (was it processed?)");
            }
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load plugin.yml", e);
        }
    }

    @Test
    void descriptorHasRequiredFields() {
        Map<String, Object> yml = loadDescriptor();
        assertEquals("ChunkLand", yml.get("name"));
        assertEquals("com.smile.chunkland.ChunkLandPlugin", yml.get("main"));
        assertEquals("26.1.2", String.valueOf(yml.get("api-version")));
        assertEquals(true, yml.get("folia-supported"));

        Object depend = yml.get("depend");
        assertInstanceOf(List.class, depend, "depend must be a list");
        assertTrue(((List<?>) depend).contains("AceLib"), "AceLib must be a hard dependency");
    }

    @Test
    @SuppressWarnings("unchecked")
    void vaultIsSoftDependSoLoadOrderFollowsWhenPresent() {
        Map<String, Object> yml = loadDescriptor();
        Object depend = yml.get("depend");
        assertFalse(depend instanceof List<?> list && list.contains("Vault"),
                "Vault must stay optional, never a hard dependency");
        Object softDepend = yml.get("softdepend");
        assertInstanceOf(List.class, softDepend, "softdepend must be a list");
        assertTrue(((List<?>) softDepend).contains("Vault"),
                "Vault must be softdepend so ChunkLand loads after it when present");
    }

    @Test
    @SuppressWarnings("unchecked")
    void coreProtectStaysOptionalSoftDepend() {
        Map<String, Object> yml = loadDescriptor();
        Object depend = yml.get("depend");
        assertFalse(depend instanceof List<?> list && list.contains("CoreProtect"),
                "CoreProtect must stay optional, never a hard dependency");
        Object softDepend = yml.get("softdepend");
        assertInstanceOf(List.class, softDepend, "softdepend must be a list");
        assertTrue(((List<?>) softDepend).contains("CoreProtect"),
                "CoreProtect must be softdepend so ChunkLand loads after it when present");
        Object permissions = yml.get("permissions");
        assertInstanceOf(Map.class, permissions, "permissions must be a map");
        Object node = ((Map<String, Object>) permissions).get("chunkland.command.land.history");
        assertInstanceOf(Map.class, node, "chunkland.command.land.history permission must exist");
        assertEquals("op", String.valueOf(((Map<String, Object>) node).get("default")));
    }

    @Test
    void versionIsExpandedNotPlaceholder() {
        Map<String, Object> yml = loadDescriptor();
        Object version = yml.get("version");
        assertEquals("0.1.0", String.valueOf(version));
        assertFalse(String.valueOf(version).contains("${"), "version must be expanded, not a placeholder");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serverlandPermissionDefaultsToOp() {
        Map<String, Object> yml = loadDescriptor();
        Object permissions = yml.get("permissions");
        assertInstanceOf(Map.class, permissions, "permissions must be a map");
        Object node = ((Map<String, Object>) permissions).get("chunkland.admin.serverland");
        assertInstanceOf(Map.class, node, "chunkland.admin.serverland permission must exist");
        assertEquals("op", String.valueOf(((Map<String, Object>) node).get("default")));
    }
}
