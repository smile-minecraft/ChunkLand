package com.smile.chunkland.protection;

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
 * Structural guards for the management-permission enforcement point.
 *
 * <p>Management mutations must pass through {@link ManagementPermissionGate}
 * before reaching the mutation pipeline: the dispatcher runs the gate check
 * itself on resolver-supplied inputs, no arbitrary allow predicate exists,
 * the formal plugin entry point carries the production resolver, and the gate
 * stays Bukkit-free so GUI/Form entry points can share it.
 */
class ManagementGateStructureTest {

    private static Path mainRoot(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(relative);
        if (Files.exists(fromHere)) {
            return fromHere;
        }
        // Gradle runs :chunkland-plugin tests with the submodule as the working
        // directory, where the "chunkland-plugin/" prefix no longer resolves.
        // Strip it so the same test passes from the repo root and the submodule.
        if (relative.startsWith("chunkland-plugin/")) {
            Path stripped = Paths.get(System.getProperty("user.dir"))
                    .resolve(relative.substring("chunkland-plugin/".length()));
            if (Files.exists(stripped)) {
                return stripped;
            }
        }
        return fromHere;
    }

    private static List<Path> productionSources(String relativeRoot) throws Exception {
        Path root = mainRoot(relativeRoot);
        List<Path> out = new ArrayList<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    private static String read(String relative) throws Exception {
        Path p = mainRoot(relative);
        assertTrue(Files.exists(p), "production source must exist: " + relative);
        return Files.readString(p);
    }

    @Test
    void gateIsBukkitFreeSoGuiAndFormCanShareIt() throws Exception {
        Path gate = mainRoot(
                "chunkland-plugin/src/main/java/com/smile/chunkland/protection/ManagementPermissionGate.java");
        assertTrue(Files.exists(gate), "ManagementPermissionGate source must exist");
        String content = Files.readString(gate);
        assertFalse(content.contains("import org.bukkit"),
                "gate must not import Bukkit; command and future GUI/Form adapt outside the gate");
        assertFalse(content.contains("org.bukkit."),
                "gate must not reference Bukkit types so Form code can share it");
    }

    @Test
    void managementEntryPointsNeverSubmitMutationsDirectly() throws Exception {
        // Every current production management entry point lives under command;
        // GUI/Form packages do not exist yet, so scan the whole main tree for
        // a mutation submit co-located with management dispatch and require
        // the gate in the same file. Executor submits (persistence close,
        // scheduler) are not management mutations and carry no gate reference.
        List<Path> sources = productionSources("chunkland-plugin/src/main/java/com/smile/chunkland");
        assertFalse(sources.isEmpty(), "production sources must be discoverable");
        for (Path p : sources) {
            String name = p.toString();
            if (name.contains("/command/") || name.contains("/gui/") || name.contains("/form/")
                    || name.contains("/management/")) {
                String content = Files.readString(p);
                if (content.contains("MutationCoordinator")
                        && content.contains(".submit(")) {
                    assertTrue(content.contains("ManagementPermissionGate"),
                            "management entry point must reference the domain gate: " + p);
                }
                assertFalse(content.contains(".submit(") && content.contains("MutationRequest")
                                && !content.contains("ManagementPermissionGate"),
                        "management entry point must go through the domain gate, never submit directly: " + p);
            }
        }
    }

    @Test
    void commandDispatcherRunsTheDomainGateItself() throws Exception {
        String content = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/LandCommand.java");
        assertTrue(content.contains("ManagementPermissionGate.check("),
                "LandCommand must run the shared domain gate check itself instead of trusting a caller verdict");
        assertTrue(content.contains("ManagementGateResolver"),
                "LandCommand must resolve gate inputs through the dedicated resolver");
    }

    @Test
    void noArbitraryAllowPredicateInProductionSources() throws Exception {
        String dispatcher = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/LandCommand.java");
        assertFalse(dispatcher.contains("BiPredicate"),
                "LandCommand must not accept an arbitrary allow predicate as the enforcement point");
        assertFalse(dispatcher.contains("managementAuthorizer"),
                "the old predicate field must be gone; the resolver supplies inputs only");
        List<Path> sources = productionSources("chunkland-plugin/src/main/java/com/smile/chunkland/command");
        for (Path p : sources) {
            String content = Files.readString(p);
            assertFalse(content.contains("BiPredicate<CommandSender, ProtectionActionType>"),
                    "no production command source may take an arbitrary allow predicate: " + p);
        }
    }

    @Test
    void formalPluginWiringCarriesTheProductionResolver() throws Exception {
        String plugin = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/ChunkLandPlugin.java");
        assertTrue(plugin.contains("ManagementGateResolver"),
                "formal plugin wiring must carry the production gate resolver");
        assertTrue(plugin.contains("buildManagementGateResolver"),
                "formal plugin wiring must build the production resolver from live snapshots");
        assertTrue(plugin.contains("PluginManagementGateResolver"),
                "formal plugin wiring must use the Bukkit-dependent production adapter");
        assertFalse(plugin.contains("new LandCommand(buildLandHandlers(), null)"),
                "formal wiring must not construct the dispatcher without a gate resolver");
        assertFalse(plugin.contains("new LandCommand(LandCommand.defaultStubHandlers(), null)"),
                "formal fallback must not construct the dispatcher without a gate resolver");
        String adapter = read(
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/PluginManagementGateResolver.java");
        assertTrue(adapter.contains("TargetLandResolver"),
                "production adapter must resolve the target land explicitly (unresolved until downstream flows)");
        assertTrue(adapter.contains(SERVER_LAND_NODE_MARKER),
                "production adapter must resolve the steward flag from the serverland node");
    }

    private static final String SERVER_LAND_NODE_MARKER = "chunkland.admin.serverland";

    @Test
    void noConcreteWorkflowIdsInNewSources() throws Exception {
        for (String relative : List.of(
                "chunkland-plugin/src/main/java/com/smile/chunkland/protection/ManagementPermissionGate.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/LandCommand.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/ManagementGateResolver.java",
                "chunkland-plugin/src/main/java/com/smile/chunkland/command/PluginManagementGateResolver.java")) {
            Path p = mainRoot(relative);
            if (!Files.exists(p)) {
                continue;
            }
            String content = Files.readString(p);
            assertFalse(content.contains("CL-M2-19"),
                    "concrete workflow ID must not appear in executable source: " + p);
        }
    }
}
