package com.smile.chunkland.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guards for the Java GUI framework: only the supported public
 * AceLib surface may be referenced, generations always come from the upstream
 * session, and disable-time cleanup never touches the provider-wide shutdown.
 */
class GuiFrameworkStructureTest {

    private static Path mainRoot(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(relative);
        if (Files.exists(fromHere)) {
            return fromHere;
        }
        if (relative.startsWith("chunkland-plugin/")) {
            Path stripped = Paths.get(System.getProperty("user.dir"))
                    .resolve(relative.substring("chunkland-plugin/".length()));
            if (Files.exists(stripped)) {
                return stripped;
            }
        }
        return fromHere;
    }

    private static List<Path> guiSources() throws Exception {
        Path root = mainRoot("chunkland-plugin/src/main/java/com/smile/chunkland/gui");
        List<Path> out = new ArrayList<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    @Test
    void guiFrameworkPackageExists() throws Exception {
        List<Path> sources = guiSources();
        assertFalse(sources.isEmpty(), "gui framework sources must exist");
    }

    @Test
    void noForbiddenAceLibGuiTypesInFramework() throws Exception {
        for (Path p : guiSources()) {
            String content = Files.readString(p);
            assertFalse(content.contains("com.smile.acelib.gui.GuiPage"),
                "framework must not reference AceLib internal GuiPage: " + p);
            assertFalse(content.contains("com.smile.acelib.gui.GuiAsyncRequest"),
                "framework must not reference AceLib internal GuiAsyncRequest: " + p);
            assertFalse(content.contains("com.smile.acelib.gui.GuiConfirmation"),
                "framework must not reference AceLib internal GuiConfirmation: " + p);
            assertFalse(content.contains("com.smile.acelib.gui.GuiState"),
                "framework must not reference AceLib GuiState: " + p);
            assertFalse(content.contains("com.smile.acelib.gui.internal"),
                "framework must not reference AceLib internals: " + p);
            assertFalse(content.contains("com.smile.acelib.impl"),
                "framework must not reference AceLib impl: " + p);
        }
    }

    @Test
    void noSelfMadeGenerationOrClickGuardInFramework() throws Exception {
        for (Path p : guiSources()) {
            String content = Files.readString(p);
            assertFalse(content.contains("new GuiSession("),
                "generations come from the upstream session, never constructed locally: " + p);
            assertFalse(content.contains("nextGeneration"),
                "framework must not mint its own click generation: " + p);
            assertFalse(content.contains("generationCounter"),
                "framework must not mint its own click generation: " + p);
            assertFalse(content.contains("stale-click token")
                    || content.contains("staleClickToken")
                    || content.contains("clickToken"),
                "framework must not invent a stale-click token: " + p);
        }
    }

    @Test
    void noProviderWideShutdownInFramework() throws Exception {
        for (Path p : guiSources()) {
            String content = Files.readString(p);
            assertFalse(content.contains(".shutdown()"),
                "framework cleanup closes tracked sessions only, never shuts down the provider: " + p);
        }
    }

    @Test
    void frameworkStaysBukkitFreeAndMutationFree() throws Exception {
        for (Path p : guiSources()) {
            String content = Files.readString(p);
            assertFalse(content.contains("import org.bukkit"),
                "framework must stay Bukkit-free so Folia region work stays with the scheduler seam: " + p);
            assertFalse(content.contains("org.bukkit."),
                "framework must not reference Bukkit types: " + p);
            assertFalse(content.contains("MutationCoordinator") && content.contains(".submit("),
                "framework must not submit mutations directly: " + p);
            assertFalse(content.contains("MutationRequest") && content.contains(".submit("),
                "framework must not submit mutations directly: " + p);
        }
    }

    @Test
    void pluginLifecycleClosesTrackedSessionsWithoutShutdown() throws Exception {
        Path plugin = mainRoot(
            "chunkland-plugin/src/main/java/com/smile/chunkland/ChunkLandPlugin.java");
        assertTrue(Files.exists(plugin), "ChunkLandPlugin source must exist");
        String content = Files.readString(plugin);
        assertTrue(content.contains("GuiNavigator"),
            "plugin lifecycle must own the GUI navigator");
        assertTrue(content.contains("closeAll()"),
            "disable must close every tracked GUI session");
    }
}
