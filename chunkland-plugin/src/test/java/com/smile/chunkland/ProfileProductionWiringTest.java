package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ProfileCommandHandler;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.protection.ManagementPermissionGate;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Production wiring for {@code /land profile}: the subcommand is registered
 * with its own permission node, the formal handler map routes to the real
 * handler instead of the not-yet stub, an unwired slot fails closed, and the
 * bilingual message keys stay in sync. The group flow keeps its own
 * registration untouched.
 */
class ProfileProductionWiringTest {

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.add(Map.copyOf(vs));
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
            reply(messageKey, vs);
        }
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hasPermission" -> true;
                    case "getName" -> "Actor";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Player-proxy";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private static Map<String, LandCommand.Handler> handlers(ProfileCommandHandler profile) {
        return ChunkLandPlugin.buildLandHandlers(
                null, null, null,
                com.smile.chunkland.selection.SelectionStructureRevisionLookup.unavailable(),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, profile);
    }

    @Test
    void subcommandIsRegisteredWithItsOwnPermissionNode() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("profile"));
        assertEquals("chunkland.command.land.profile", LandPermissions.PROFILE);
        assertEquals(LandPermissions.PROFILE, LandPermissions.forSubcommand("profile"));
        assertEquals(LandPermissions.PROFILE, LandPermissions.forSubcommand("PROFILE"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.PROFILE));
        assertNotEquals(LandPermissions.GROUP, LandPermissions.PROFILE);
        assertEquals(Optional.empty(), LandCommand.managementActionFor("profile"));
        assertEquals(Optional.empty(), ManagementPermissionGate.actionForSubcommand("profile"));
    }

    @Test
    void formalHandlerMapRoutesProfileToRealHandler() {
        ProfileCommandHandler handler = new ProfileCommandHandler(
                new ProfileCommandHandler.Profiles() {
                    @Override
                    public java.util.concurrent.CompletionStage<
                            PermissionProfileRepository.ProfileView> create(
                            UUID owner, String name) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<List<
                            PermissionProfileRepository.ProfileView>> list(UUID owner) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            PermissionProfileRepository.ProfileView> resolve(
                            UUID owner, String ref) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            PermissionProfileRepository.EntryOutcome> setEntry(UUID owner,
                            UUID profileId, ProtectionActionType action, PermissionState state) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> delete(
                            UUID owner, UUID profileId) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            PermissionProfileRepository.ForceDeleteOutcome> forceDelete(
                            UUID owner, UUID profileId) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }
                });

        Map<String, LandCommand.Handler> wired = handlers(handler);

        assertSame(handler, wired.get("profile"),
                "formal /land wiring must assemble the real profile handler, not the stub");
    }

    @Test
    void unwiredProfileSlotFailsClosedInsteadOfStub() {
        Map<String, LandCommand.Handler> wired = handlers(null);
        CapturingSink sink = new CapturingSink();

        wired.get("profile").handle(
                player(UUID.randomUUID()), new String[] {"profile", "list"}, sink);

        assertEquals(1, sink.keys.size());
        assertNotEquals("command.land.not_yet", sink.keys.get(0),
                "an unwired profile slot must fail closed, never fall back to the stub");
        assertEquals("command.land.profile.failed", sink.keys.get(0));
        assertEquals("profile.unavailable", sink.vars.get(0).get("reason"));
    }

    @Test
    void groupSlotSurvivesShorterOverloadsAsLegacyStub() {
        Map<String, LandCommand.Handler> wired = ChunkLandPlugin.buildLandHandlers();
        CapturingSink sink = new CapturingSink();

        wired.get("group").handle(
                player(UUID.randomUUID()), new String[] {"group", "list"}, sink);

        // The base map keeps the legacy stub for overloads that predate the
        // group flow; the formal overload above is what production uses.
        assertEquals("command.land.not_yet", sink.keys.get(0));
    }

    @Test
    void profileMessagesAreBilingual() throws Exception {
        Set<String> enKeys = commandLandProfileKeys("en_US");
        Set<String> zhKeys = commandLandProfileKeys("zh_TW");
        assertEquals(
                Set.of("command.land.profile.console", "command.land.profile.usage",
                        "command.land.profile.created", "command.land.profile.listed",
                        "command.land.profile.listed_empty", "command.land.profile.set",
                        "command.land.profile.deleted",
                        "command.land.profile.force_deleted", "command.land.profile.failed"),
                enKeys);
        assertEquals(enKeys, zhKeys);
    }

    private static Set<String> commandLandProfileKeys(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        Set<String> out = new HashSet<>();
        for (String key : cfg.getKeys(true)) {
            Object value = cfg.get(key);
            if (value instanceof String && key.startsWith("command.land.profile.")) {
                out.add(key);
            }
        }
        return out;
    }
}
