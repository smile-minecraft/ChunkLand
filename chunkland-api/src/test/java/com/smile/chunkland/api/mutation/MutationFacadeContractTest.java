package com.smile.chunkland.api.mutation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Stable public mutation facade contract.
 */
class MutationFacadeContractTest {

    private static final Path MUTATION_SRC =
            Paths.get("src/main/java/com/smile/chunkland/api/mutation");

    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(org\\.bukkit\\.|org\\.paper\\.|java\\.sql\\.|javax\\.sql\\."
                    + "|net\\.milkbowl\\.|com\\.earth2me\\.|com\\.smile\\.acelib\\.)",
            Pattern.MULTILINE);

    private static List<Path> sources() throws IOException {
        if (!Files.isDirectory(MUTATION_SRC)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(MUTATION_SRC)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    @Test
    void facadeIsStableInterfaceWithCompletionStageSubmit() throws Exception {
        assertTrue(ChunkLandMutations.class.isInterface(), "facade must be an interface");
        Method submit = ChunkLandMutations.class.getMethod("submit", MutationRequest.class);
        assertTrue(CompletionStage.class.isAssignableFrom(submit.getReturnType()),
                "submit must return CompletionStage");
        assertTrue(Modifier.isAbstract(submit.getModifiers()) || submit.isDefault());
    }

    @Test
    void submitReturnsStageWithoutBlockingCaller() throws Exception {
        ChunkLandMutations facade = request -> new CompletableFuture<MutationResult>();
        MutationRequest req = new MutationRequest(
                MutationKind.LAND_CREATE, null, OwnerRef.server(),
                Set.of(new ChunkKey(UUID.randomUUID(), 0, 0)), "Home");
        long start = System.nanoTime();
        CompletionStage<MutationResult> stage = facade.submit(req);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNotNull(stage, "submit must never return null");
        assertTrue(elapsedMs < 1000, "submit must not block caller, took " + elapsedMs + "ms");
    }

    @Test
    void requestAndResultStayImmutable() {
        UUID world = UUID.randomUUID();
        Set<ChunkKey> mutable = new HashSet<>();
        mutable.add(new ChunkKey(world, 0, 0));
        MutationRequest req = new MutationRequest(
                MutationKind.LAND_CREATE, null, OwnerRef.server(), mutable, "Home");
        mutable.add(new ChunkKey(world, 9, 9));
        assertEquals(1, req.chunks().size());
        assertThrows(UnsupportedOperationException.class,
                () -> req.chunks().add(new ChunkKey(world, 1, 1)));
        MutationResult ok = MutationResult.success(new LandId(UUID.randomUUID()));
        assertNotNull(ok.outcome());
    }

    @Test
    void publicSurfaceExposesNoMutableDomainCollections() throws Exception {
        for (Method m : ChunkLandMutations.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p.getName().contains("LandRegistry"),
                        "public surface must not accept LandRegistry");
                assertFalse(p.getName().contains("Mutable"),
                        "public surface must not accept mutable domain type " + p);
            }
            assertFalse(m.getReturnType().getName().contains("LandRegistry"),
                    "public surface must not return LandRegistry");
        }
        for (Method m : MutationResult.class.getDeclaredMethods()) {
            assertFalse(java.util.Collection.class.isAssignableFrom(m.getReturnType())
                            && Modifier.isStatic(m.getModifiers()) == false
                            && m.getParameterCount() == 0
                            && m.getName().equals("chunks"),
                    "result must not expose mutable chunk collection");
        }
    }

    @Test
    void everyPublicMutationTypeCarriesSince() {
        for (Class<?> type : List.of(
                ChunkLandMutations.class,
                MutationKind.class,
                MutationOutcome.class,
                MutationRequest.class,
                MutationResult.class)) {
            boolean hasSince = type.getPackageName() != null
                    && hasSinceTag(type);
            assertTrue(hasSince, type.getSimpleName() + " must carry @since");
        }
    }

    private static boolean hasSinceTag(Class<?> type) {
        String loc = "/com/smile/chunkland/api/mutation/" + type.getSimpleName() + ".java";
        // Fallback: source scan from module dir when run via Gradle.
        Path direct = MUTATION_SRC.resolve(type.getSimpleName() + ".java");
        Path alt = Paths.get("chunkland-api/src/main/java/com/smile/chunkland/api/mutation")
                .resolve(type.getSimpleName() + ".java");
        Path file = Files.isRegularFile(direct) ? direct : alt;
        try {
            String src = Files.readString(file);
            return src.contains("@since");
        } catch (IOException e) {
            fail("cannot read source for " + type.getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    @Test
    void mutationPackageStaysDependencyFree() throws IOException {
        List<Path> files = sources();
        assertFalse(files.isEmpty(), "mutation package must contain sources when run from the module dir");
        for (Path file : files) {
            String source = Files.readString(file);
            if (FORBIDDEN_IMPORT.matcher(source).find()) {
                fail("Forbidden dependency import in " + file.getFileName());
            }
        }
    }

    @Test
    void executableSourcesCarryNoWorkflowIds() throws IOException {
        Pattern workflowId = Pattern.compile("M5-\\d{2}|CL-M5-\\d{2}");
        List<Path> files = sources();
        assertFalse(files.isEmpty(), "mutation package must contain sources when run from the module dir");
        for (Path file : files) {
            String source = Files.readString(file);
            if (workflowId.matcher(source).find()) {
                fail("Workflow ID found in " + file.getFileName());
            }
        }
        String self = Files.readString(Paths.get(
                "src/test/java/com/smile/chunkland/api/mutation/MutationFacadeContractTest.java"));
        if (workflowId.matcher(self).find()) {
            fail("Workflow ID found in MutationFacadeContractTest");
        }
    }

    @Test
    void facadeJavadocStatesContractOnlyScope() throws IOException {
        Path direct = MUTATION_SRC.resolve("ChunkLandMutations.java");
        Path alt = Paths.get("chunkland-api/src/main/java/com/smile/chunkland/api/mutation")
                .resolve("ChunkLandMutations.java");
        Path file = Files.isRegularFile(direct) ? direct : alt;
        String source = Files.readString(file);
        assertTrue(source.contains("No default plugin instance"),
                "facade Javadoc must state contract-only scope with no default plugin instance");
    }
}
