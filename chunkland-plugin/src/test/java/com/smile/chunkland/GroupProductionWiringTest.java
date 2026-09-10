package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.command.GroupCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ReplySink;
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
 * Production wiring for {@code /land group}: the subcommand is registered
 * with its own permission node, the formal handler map routes to the real
 * handler instead of the not-yet stub, an unwired slot fails closed, and the
 * bilingual message keys stay in sync.
 */
class GroupProductionWiringTest {

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

    private static Map<String, LandCommand.Handler> handlers(GroupCommandHandler group) {
        return ChunkLandPlugin.buildLandHandlers(
                null, null, null,
                com.smile.chunkland.selection.SelectionStructureRevisionLookup.unavailable(),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                group);
    }

    @Test
    void subcommandIsRegisteredWithItsOwnPermissionNode() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("group"));
        assertEquals("chunkland.command.land.group", LandPermissions.GROUP);
        assertEquals(LandPermissions.GROUP, LandPermissions.forSubcommand("group"));
        assertEquals(LandPermissions.GROUP, LandPermissions.forSubcommand("GROUP"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.GROUP));
        assertEquals(Optional.empty(), LandCommand.managementActionFor("group"));
        assertEquals(Optional.empty(), ManagementPermissionGate.actionForSubcommand("group"));
    }

    @Test
    void formalHandlerMapRoutesGroupToRealHandler() {
        GroupCommandHandler handler = new GroupCommandHandler(
                new GroupCommandHandler.Groups() {
                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.smile.chunkland.persistence.SubjectGroupRepository.GroupView> create(
                            UUID owner, String name) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<List<
                            com.smile.chunkland.persistence.SubjectGroupRepository.GroupView>> list(
                            UUID owner) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.smile.chunkland.persistence.SubjectGroupRepository.GroupView> resolve(
                            UUID owner, String ref) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.smile.chunkland.persistence.SubjectGroupRepository.MembershipOutcome>
                            addMember(UUID owner, UUID groupId, UUID member) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.smile.chunkland.persistence.SubjectGroupRepository.MembershipOutcome>
                            removeMember(UUID owner, UUID groupId, UUID member) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<Void> delete(
                            UUID owner, UUID groupId) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.smile.chunkland.persistence.SubjectGroupRepository.ForceDeleteOutcome>
                            forceDelete(UUID owner, UUID groupId) {
                        return CompletableFuture.failedFuture(new IllegalStateException("unused"));
                    }
                }, name -> Optional.empty());

        Map<String, LandCommand.Handler> wired = handlers(handler);

        assertSame(handler, wired.get("group"),
                "formal /land wiring must assemble the real group handler, not the stub");
    }

    @Test
    void unwiredGroupSlotFailsClosedInsteadOfStub() {
        Map<String, LandCommand.Handler> wired = handlers(null);
        CapturingSink sink = new CapturingSink();

        wired.get("group").handle(
                player(UUID.randomUUID()), new String[] {"group", "list"}, sink);

        assertEquals(1, sink.keys.size());
        assertNotEquals("command.land.not_yet", sink.keys.get(0),
                "an unwired group slot must fail closed, never fall back to the stub");
        assertEquals("command.land.group.failed", sink.keys.get(0));
        assertEquals("group.unavailable", sink.vars.get(0).get("reason"));
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
    void groupMessagesAreBilingual() throws Exception {
        Set<String> enKeys = commandLandGroupKeys("en_US");
        Set<String> zhKeys = commandLandGroupKeys("zh_TW");
        assertEquals(
                Set.of("command.land.group.console", "command.land.group.usage",
                        "command.land.group.created", "command.land.group.listed",
                        "command.land.group.listed_empty", "command.land.group.added",
                        "command.land.group.removed", "command.land.group.deleted",
                        "command.land.group.force_deleted", "command.land.group.failed"),
                enKeys);
        assertEquals(enKeys, zhKeys);
    }

    private static Set<String> commandLandGroupKeys(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        Set<String> out = new HashSet<>();
        for (String key : cfg.getKeys(true)) {
            Object value = cfg.get(key);
            if (value instanceof String && key.startsWith("command.land.group.")) {
                out.add(key);
            }
        }
        return out;
    }
}
