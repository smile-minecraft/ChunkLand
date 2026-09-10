package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.command.BindingCommandHandler;
import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.LandPermissions;
import com.smile.chunkland.command.ManagementGateResolver;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.protection.SnapshotPermissionContextProvider;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.runtime.api.PermissionContextProvider;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Production wiring for {@code /land binding}: the subcommand is registered
 * with its own permission node, gated by {@code MANAGE_PERMISSION} through
 * the shared domain gate, the formal handler map routes to the real handler
 * instead of a stub, an unwired slot fails closed, and the bilingual message
 * keys stay in sync.
 */
class BindingProductionWiringTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

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

    private static CommandSender senderWithAllNodes() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "isPermissionSet" -> true;
                    case "getName" -> "Sender";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Sender-proxy";
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) {
                            yield false;
                        }
                        if (rt == int.class) {
                            yield 0;
                        }
                        yield null;
                    }
                });
    }

    private static Map<String, LandCommand.Handler> handlers(BindingCommandHandler binding) {
        return ChunkLandPlugin.buildLandHandlers(
                null, null, null,
                com.smile.chunkland.selection.SelectionStructureRevisionLookup.unavailable(),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, binding);
    }

    private static PermissionContextProvider emptyProvider() {
        return new SnapshotPermissionContextProvider(null, null);
    }

    private static ManagementGateResolver.Request requestFor(UUID actor) {
        LandId id = new LandId(UUID.randomUUID());
        LandName name = LandName.of("Home");
        LandSnapshot land = new LandSnapshot(id, name.displayName(), name.nameKey(),
                OwnerRef.player(OWNER), WORLD, Set.of(new ChunkKey(WORLD, 0, 0)),
                List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
        return new ManagementGateResolver.Request(actor, id, LandRegistry.from(List.of(land)),
                false, false, emptyProvider());
    }

    @Test
    void subcommandIsRegisteredWithItsOwnPermissionNodeAndGate() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("binding"));
        assertEquals("chunkland.command.land.binding", LandPermissions.BINDING);
        assertEquals(LandPermissions.BINDING, LandPermissions.forSubcommand("binding"));
        assertEquals(LandPermissions.BINDING, LandPermissions.forSubcommand("BINDING"));
        assertTrue(LandPermissions.ALL_ORDERED.contains(LandPermissions.BINDING));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                LandCommand.managementActionFor("binding"));
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                ManagementPermissionGate.actionForSubcommand("binding"));
        assertTrue(ManagementPermissionGate.isManagementAction(ProtectionActionType.MANAGE_PERMISSION));
    }

    @Test
    void formalHandlerMapRoutesBindingToRealHandler() {
        BindingCommandHandler handler = new BindingCommandHandler(null, null, null, null, null,
                null);
        Map<String, LandCommand.Handler> wired = handlers(handler);

        assertSame(handler, wired.get("binding"),
                "formal /land wiring must assemble the real binding handler, not the stub");
    }

    @Test
    void unwiredBindingSlotFailsClosedInsteadOfStub() {
        Map<String, LandCommand.Handler> wired = handlers(null);
        CapturingSink sink = new CapturingSink();

        wired.get("binding").handle(
                player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(1, sink.keys.size());
        assertNotEquals("command.land.not_yet", sink.keys.get(0),
                "an unwired binding slot must fail closed, never fall back to the stub");
        assertEquals("command.land.binding.failed", sink.keys.get(0));
        assertEquals("binding.unavailable", sink.vars.get(0).get("reason"));
    }

    @Test
    void baseStubFailsClosedInsteadOfNotYet() {
        LandCommand.Handler stub = LandCommand.defaultStubHandlers().get("binding");
        CapturingSink sink = new CapturingSink();

        stub.handle(player(UUID.randomUUID()), new String[] {"binding"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
    }

    @Test
    void domainDenyRefusesBindingEvenWhenBukkitNodePasses() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlerMap =
                Map.of("binding", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        ManagementGateResolver resolver = (sender, action, args) ->
                Optional.of(requestFor(UUID.randomUUID()));
        LandCommand cmd = new LandCommand(handlerMap, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, Locale l) {
                keys.add(k);
            }
        }, resolver);

        assertTrue(cmd.dispatch(senderWithAllNodes(), new String[] {"binding"}, null));
        assertEquals(false, called.get(), "domain deny must not invoke handler");
        assertTrue(keys.contains("command.land.denied"), "domain deny must reply denied");
    }

    @Test
    void domainAllowReachesBindingHandler() {
        AtomicBoolean called = new AtomicBoolean(false);
        Map<String, LandCommand.Handler> handlerMap =
                Map.of("binding", (s, a, sink) -> called.set(true));
        List<String> keys = new ArrayList<>();
        ManagementGateResolver.Request request = requestFor(OWNER);
        assertEquals(PermissionState.ALLOW, ManagementPermissionGate.check(
                request.actor(), request.landId(), ProtectionActionType.MANAGE_PERMISSION,
                request.snapshot(), request.adminBypass(), request.serverLandSteward(),
                request.provider()).outcome());
        ManagementGateResolver resolver = (sender, action, args) -> Optional.of(request);
        LandCommand cmd = new LandCommand(handlerMap, (s, p) -> new ReplySink() {
            public void reply(String k, Map<String, Object> v) {
                keys.add(k);
            }

            public void reply(String k, Map<String, Object> v, Locale l) {
                keys.add(k);
            }
        }, resolver);

        assertTrue(cmd.dispatch(senderWithAllNodes(), new String[] {"binding"}, null));
        assertTrue(called.get(), "domain allow must invoke handler");
        assertTrue(keys.isEmpty(), "domain allow must not reply denied");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pluginDescriptorCarriesBindingNodeDefaultOp() {
        Map<String, Object> yml;
        try (InputStream in = getClass().getResourceAsStream("/plugin.yml")) {
            yml = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load plugin.yml", e);
        }
        Object permissions = yml.get("permissions");
        assertTrue(permissions instanceof Map, "permissions must be a map");
        Object node = ((Map<String, Object>) permissions).get("chunkland.command.land.binding");
        assertTrue(node instanceof Map, "binding permission node must exist");
        assertEquals("op", String.valueOf(((Map<String, Object>) node).get("default")));
    }

    @Test
    void bindingMessagesAreBilingual() throws Exception {
        Set<String> enKeys = commandLandBindingKeys("en_US");
        Set<String> zhKeys = commandLandBindingKeys("zh_TW");
        assertEquals(
                Set.of("command.land.binding.console", "command.land.binding.usage",
                        "command.land.binding.created", "command.land.binding.updated",
                        "command.land.binding.deleted", "command.land.binding.failed"),
                enKeys);
        assertEquals(enKeys, zhKeys);
    }

    private static Set<String> commandLandBindingKeys(String tag) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.load(new File("src/main/resources/lang/" + tag + ".yml"));
        Set<String> out = new HashSet<>();
        for (String key : cfg.getKeys(true)) {
            Object value = cfg.get(key);
            if (value instanceof String && key.startsWith("command.land.binding.")) {
                out.add(key);
            }
        }
        return out;
    }

    @Test
    void bindingSlotSurvivesShorterOverloadsAsFailClosedStub() {
        Map<String, LandCommand.Handler> wired = ChunkLandPlugin.buildLandHandlers();
        CapturingSink sink = new CapturingSink();

        wired.get("binding").handle(
                player(UUID.randomUUID()), new String[] {"binding"}, sink);

        assertEquals("command.land.binding.failed", sink.keys.get(0));
    }
}
