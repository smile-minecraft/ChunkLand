package com.smile.chunkland.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.Permission;
import com.smile.chunkland.api.permission.PermissionBinding;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.PermissionSubject;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Boundary of the atomic authorisation mechanism, recorded so no later
 * change can silently widen or narrow it.
 *
 * <p>Covered atomically (inside the write transaction): the durable
 * authorisation generation ({@code lands.land_policy_revision}) plus the
 * default value itself. Memory-only sources — config file defaults, admin
 * bypass, the server-land steward flag — never enter the transaction;
 * their revocation is observed only by re-checking
 * {@link ManagementPermissionGate} before submitting. A reader that cannot
 * see a source cannot pin it, so the split is structural, not a choice.
 */
class AuthorisationBoundaryTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static LandSnapshot playerLand(LandId id) {
        LandName name = LandName.of("Home");
        return new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionContextProvider providerFor(
            SubjectPermissionLookup.Grant grant) {
        return new SnapshotPermissionContextProvider(null,
                (actor, landId, action, snapshot) -> grant);
    }

    private static SubjectPermissionLookup.Grant grantWith(
            List<PermissionBinding> bindings) {
        return new SubjectPermissionLookup.Grant(bindings, PermissionState.INHERIT,
                PermissionState.INHERIT, PermissionState.INHERIT);
    }

    private static PermissionBinding allow(UUID actor, ProtectionActionType action) {
        return new PermissionBinding(PermissionSubject.player(actor),
                new Permission(action, PermissionState.ALLOW));
    }

    @Test
    void memorySourceRevocationIsObservedOnlyByRecheckingTheGate() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id)));
        ProtectionActionType action = ProtectionActionType.MANAGE_PERMISSION;

        var granted = ManagementPermissionGate.check(STRANGER, id, action, snapshot,
                false, false, providerFor(grantWith(List.of(allow(STRANGER, action)))));
        assertEquals(PermissionState.ALLOW, granted.outcome(),
                "a memory-source grant passes the gate");

        var revoked = ManagementPermissionGate.check(STRANGER, id, action, snapshot,
                false, false, providerFor(grantWith(List.of())));
        assertEquals(PermissionState.DENY, revoked.outcome(),
                "the same revocation is observed only by re-checking the gate;"
                        + " the write transaction itself cannot see it");
    }

    @Test
    void adminBypassRevocationIsObservedOnlyByRecheckingTheGate() {
        LandId id = new LandId(UUID.randomUUID());
        LandRegistry snapshot = LandRegistry.from(List.of(playerLand(id)));
        ProtectionActionType action = ProtectionActionType.MANAGE_PERMISSION;
        PermissionContextProvider empty = providerFor(grantWith(List.of()));

        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, true, false, empty).outcome());
        assertEquals(PermissionState.DENY, ManagementPermissionGate.check(
                STRANGER, id, action, snapshot, false, false, empty).outcome(),
                "a flipped bypass flag changes nothing durable, so only the"
                        + " pre-submit gate can observe it");
    }

    /**
     * Tokens a memory-only authorisation source would need inside the
     * conditional write. If any ever appears there, this test fails on
     * purpose: update the boundary documentation first.
     */
    private static final List<String> NON_DURABLE_TOKENS = List.of(
            "bypass", "Bypass", "steward", "Steward", "config", "Config",
            "world_default", "global_default", "PermissionDefaults", "AdminBypass");

    private static final List<String> DURABLE_PIN_TOKENS = List.of(
            "readPolicyRevision", "readDefaultState", "StaleAuthorisationException",
            "LandDefaultConflictException");

    private static String pinMethodSource() throws Exception {
        return extractPinMethod(repositorySource());
    }

    private static String repositorySource() throws Exception {
        String relative =
                "chunkland-plugin/src/main/java/com/smile/chunkland/persistence/LandAuthorisationRepository.java";
        for (String candidate : List.of(relative,
                "src/main/java/com/smile/chunkland/persistence/LandAuthorisationRepository.java")) {
            Path direct = Paths.get(candidate);
            if (Files.exists(direct)) {
                return Files.readString(direct);
            }
            Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(candidate);
            if (Files.exists(fromHere)) {
                return Files.readString(fromHere);
            }
        }
        throw new IllegalStateException("cannot locate LandAuthorisationRepository.java");
    }

    private static String pluginSource() throws Exception {
        String relative =
                "chunkland-plugin/src/main/java/com/smile/chunkland/ChunkLandPlugin.java";
        for (String candidate : List.of(relative,
                "src/main/java/com/smile/chunkland/ChunkLandPlugin.java")) {
            Path direct = Paths.get(candidate);
            if (Files.exists(direct)) {
                return Files.readString(direct);
            }
            Path fromHere = Paths.get(System.getProperty("user.dir")).resolve(candidate);
            if (Files.exists(fromHere)) {
                return Files.readString(fromHere);
            }
        }
        throw new IllegalStateException("cannot locate ChunkLandPlugin.java");
    }

    private static String extractPinMethod(String source) {
        int start = source.indexOf("setDefaultIfCurrent");
        if (start < 0) {
            throw new IllegalStateException("pin method not found");
        }
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new IllegalStateException("pin method body never closes");
    }

    @Test
    void conditionalWriteReferencesOnlyDurableState() throws Exception {
        String body = pinMethodSource();
        for (String token : DURABLE_PIN_TOKENS) {
            assertTrue(body.contains(token),
                    "the pin must keep verifying " + token);
        }
        for (String token : NON_DURABLE_TOKENS) {
            assertFalse(body.contains(token),
                    "memory-only source '" + token + "' must never enter the write"
                            + " transaction; its revocation stays gate-only by design");
        }
    }

    /**
     * One-level call-chain scan: the pin plus every private helper it
     * invokes directly. A memory-only source smuggled through a helper
     * that carries none of the banned tokens in its own name trips this
     * even though the pin body stays clean.
     *
     * <p>Deliberately one level: infrastructure outside this file
     * ({@code submitAsync}, the transaction runner, JDK calls) is out of
     * scope, and deeper chains would couple this test to every refactor.
     * Widening past one level, or touching the infrastructure, needs a
     * reviewer, which is the actual defence for those layers.
     */
    @Test
    void conditionalWriteChainReferencesOnlyDurableState() throws Exception {
        String source = repositorySource();
        String pin = extractPinMethod(source);
        java.util.Set<String> chain = new java.util.LinkedHashSet<>();
        chain.add(pin);
        for (String name : privateStaticMethods(source)) {
            if (invokes(pin, name)) {
                extractDeclaration(source, name).ifPresent(chain::add);
            }
        }
        assertTrue(chain.size() > 1, "the scan must actually follow helpers");
        String combined = String.join("\n", chain);
        for (String token : DURABLE_PIN_TOKENS) {
            assertTrue(combined.contains(token),
                    "the pin chain must keep verifying " + token);
        }
        for (String token : NON_DURABLE_TOKENS) {
            assertFalse(combined.contains(token),
                    "memory-only source '" + token + "' must never enter the write"
                            + " transaction, not even through a helper");
        }
    }

    /**
     * Both write paths must target the same atomic entry: the GUI seam
     * and the {@code /land default} handler wiring. A rewire back to the
     * unconditional entry is already a compile error (the shared seam
     * carries the pins in its arity); this pins the wiring so the revert
     * cannot slip through a comment-sized diff unnoticed.
     */
    @Test
    void bothWritePathsRouteThroughTheAtomicEntry() throws Exception {
        String plugin = pluginSource();
        int atomicWires = plugin.split("authorisations::setDefaultExpected", -1).length - 1;
        assertTrue(atomicWires >= 2,
                "the GUI seam and the /land default handler must both target"
                        + " setDefaultExpected, found " + atomicWires);
        assertFalse(java.util.regex.Pattern.compile("::setDefault(?!Expected)")
                        .matcher(plugin).find(),
                "no write path may route back to the unconditional entry");
    }

    private static java.util.Set<String> privateStaticMethods(String source) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "private\\s+static\\s+.+?\\s+(\\w+)\\s*\\(")
                .matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean invokes(String body, String name) {
        return java.util.regex.Pattern.compile(
                        "(?<![\\w.])" + java.util.regex.Pattern.quote(name) + "\\s*\\(")
                .matcher(body).find();
    }

    private static java.util.Optional<String> extractDeclaration(String source, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "(?<![\\w.])" + java.util.regex.Pattern.quote(name) + "\\s*\\(")
                .matcher(source);
        while (matcher.find()) {
            int lineStart = source.lastIndexOf('\n', matcher.start()) + 1;
            String head = source.substring(lineStart, matcher.start());
            if ((head.contains("private") || head.contains("public"))
                    && !head.contains(";")) {
                int depth = 0;
                for (int i = matcher.end() - 1; i < source.length(); i++) {
                    char c = source.charAt(i);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            return java.util.Optional.of(
                                    source.substring(matcher.start(), i + 1));
                        }
                    }
                }
                throw new IllegalStateException("body never closes: " + name);
            }
        }
        return java.util.Optional.empty();
    }
}
