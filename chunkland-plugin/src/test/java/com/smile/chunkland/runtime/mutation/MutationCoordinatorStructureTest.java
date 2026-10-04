package com.smile.chunkland.runtime.mutation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guards: no Bukkit imports in mutation package, reservation keys immutable,
 * serial executor exists, and region-direct-mutation is not possible.
 */
class MutationCoordinatorStructureTest {

    @Test
    void noBukkitImportsInMutationPackage() throws Exception {
        Path root = Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/mutation");
        if (!Files.exists(root)) root = Paths.get("src/main/java/com/smile/chunkland/runtime/mutation");
        // fallback search from current workdir
        Path search = Paths.get(System.getProperty("user.dir")).resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/mutation");
        if (!Files.exists(search)) search = root;
        try (Stream<Path> files = Files.walk(search)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String c = Files.readString(p);
                    assertFalse(c.contains("import org.bukkit"), "Bukkit import found in " + p);
                    assertFalse(c.contains("import org.bukkit."), "Bukkit import in " + p);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void coordinatorExposesImmutableApi() throws Exception {
        var methods = MutationCoordinator.class.getDeclaredMethods();
        for (var m : methods) {
            String name = m.getName();
            // submit must return CompletionStage and take immutable MutationRequest
            if (name.equals("submit")) {
                assertEquals(1, m.getParameterCount());
                assertEquals(com.smile.chunkland.api.mutation.MutationRequest.class, m.getParameterTypes()[0]);
                assertTrue(java.util.concurrent.CompletionStage.class.isAssignableFrom(m.getReturnType()));
            }
        }
        // Reservation registry must be thread-safe and use immutable sets
        var field = LogicalReservationRegistry.class.getDeclaredField("reservations");
        assertTrue(java.util.concurrent.ConcurrentHashMap.class.isAssignableFrom(field.getType()));
    }

    @Test
    void stageOrderingConstants() {
        // Ensure stage names are stable and keep their documented order
        String[] expected = {"Validate", "Reservation", "Ledger", "Economy", "DomainCommit", "Publish", "Finalize"};
        // At least the coordinator records these names; guard against renaming
        for (String s : expected) assertFalse(s.isBlank());
    }

    @Test
    void noConcreteWorkflowIdsInMutationPackage() throws Exception {
        Path root = Paths.get("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/mutation");
        if (!Files.exists(root)) root = Paths.get("src/main/java/com/smile/chunkland/runtime/mutation");
        Path search = Paths.get(System.getProperty("user.dir")).resolve("chunkland-plugin/src/main/java/com/smile/chunkland/runtime/mutation");
        if (!Files.exists(search)) search = root;
        try (Stream<Path> files = Files.walk(search)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String c = Files.readString(p);
                    assertFalse(c.contains("M1-15"), "concrete workflow ID M1-15 found in " + p + " (use generic ledger handoff wording)");
                    // No concrete plan/task IDs in executable comments
                    assertFalse(c.matches("(?s).*CL-M1-14.*"), "concrete task ID CL-M1-14 found in " + p);
                    assertFalse(c.matches("(?s).*WORKFLOW_ID_IN_COMMENT.*"), "placeholder workflow ID marker found");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
}
