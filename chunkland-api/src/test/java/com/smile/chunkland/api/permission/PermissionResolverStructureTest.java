package com.smile.chunkland.api.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionSubject.Kind;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structure test: the permission resolver package must stay pure — no Bukkit, no
 * SQL, no AceLib / external dependency, and no global mutable cache or static
 * mutable state. The api production classpath already forbids
 * external dependencies; this test pins the resolver's own shape so a
 * regression is caught at the unit level.
 */
class PermissionResolverStructureTest {

    private static final Set<Class<?>> PERMISSION_TYPES = Set.of(
            Permission.class,
            PermissionState.class,
            DecisionSource.class,
            ProtectionActionType.class,
            PermissionSubject.class,
            PermissionBinding.class,
            PermissionContext.class,
            PermissionDecision.class,
            PermissionResolver.class);

    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "org.bukkit",
            "java.sql",
            "javax.sql",
            "net.milkbowl", // Vault
            "com.mojang",
            "org.spigotmc");

    @Test
    void noStaticMutableStateNoGlobalCache() {
        for (var c : PERMISSION_TYPES) {
            for (var f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                boolean staticNonFinal = Modifier.isStatic(m) && !Modifier.isFinal(m);
                assertTrue(!staticNonFinal,
                        c.getSimpleName() + " has a static non-final field (global mutable cache): " + f.getName());
            }
        }
    }

    @Test
    void resolverIsStatelessUtility() {
        for (var f : PermissionResolver.class.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(f.getModifiers()),
                    "PermissionResolver must hold no instance state: " + f.getName());
        }
        assertTrue(Modifier.isFinal(PermissionResolver.class.getModifiers()),
                "PermissionResolver should be final (no subclassing of a stateless utility)");
    }

    @Test
    void noForbiddenTypeReferences() {
        for (var c : PERMISSION_TYPES) {
            assertNoForbidden(c.getName(), c.getDeclaredFields());
            for (var mt : c.getDeclaredMethods()) {
                assertNoForbidden(c.getName() + "#" + mt.getName(), mt.getParameters());
                if (mt.getReturnType() != void.class) {
                    assertNoForbiddenType(c.getName() + "#" + mt.getName() + " return", mt.getReturnType());
                }
            }
        }
    }

    private static void assertNoForbidden(String where, Field[] fields) {
        for (var f : fields) {
            assertNoForbiddenType(where + " field " + f.getName(), f.getType());
        }
    }

    private static void assertNoForbidden(String where, java.lang.reflect.Parameter[] params) {
        for (var p : params) {
            assertNoForbiddenType(where + " param " + p.getName(), p.getType());
        }
    }

    private static void assertNoForbiddenType(String where, Class<?> type) {
        // Walk component types for arrays.
        Class<?> t = type.isArray() ? type.getComponentType() : type;
        String pkg = t.getPackage() == null ? "" : t.getPackage().getName();
        String lower = pkg.toLowerCase();
        for (var forbidden : FORBIDDEN_PREFIXES) {
            assertTrue(!lower.startsWith(forbidden),
                    where + " references forbidden package '" + forbidden + "' via type " + t.getName());
        }
        // AceLib lives under a package containing "acelib".
        assertTrue(!lower.contains("acelib"),
                where + " references AceLib via type " + t.getName());
    }

    @Test
    void subjectKindHasNoEveryoneValue() {
        // Reinforces the contract at the type level: only PLAYER and GROUP exist.
        assertEquals(2, Kind.values().length);
        assertEquals("PLAYER", Kind.PLAYER.name());
        assertEquals("GROUP", Kind.GROUP.name());
    }

    @Test
    void resolverEntryPointIsStaticPure() throws NoSuchMethodException {
        var resolve = PermissionResolver.class.getDeclaredMethod("resolve", PermissionContext.class);
        assertTrue(Modifier.isStatic(resolve.getModifiers()), "resolve must be a static pure function");
        assertEquals(PermissionDecision.class, resolve.getReturnType());
    }

    @Test
    void contextBindingsViewIsUnmodifiable() {
        var c = new PermissionContext(
                ProtectionActionType.BLOCK_BREAK, false, List.of(), PermissionState.INHERIT, PermissionState.INHERIT);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> c.subjectBindings().add(null));
    }
}
