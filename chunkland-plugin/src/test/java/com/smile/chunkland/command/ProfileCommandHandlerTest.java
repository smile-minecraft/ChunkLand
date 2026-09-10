package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.ProfileDeleteRestrictedException;
import com.smile.chunkland.persistence.ProfileRejectedException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land profile} resolves the sender's own namespace and parses
 * permission/state text, then runs the durable operation exactly once; every
 * unresolvable input fails closed with zero side effects and never answers
 * {@code not_yet}.
 */
class ProfileCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final PermissionProfileRepository.ProfileView PROFILE =
            new PermissionProfileRepository.ProfileView(PROFILE_ID, ACTOR, "Builders",
                    "builders", System.currentTimeMillis(), Map.of());

    private static final class Calls {
        final CopyOnWriteArrayList<String> ops = new CopyOnWriteArrayList<>();
    }

    private static class FakeProfiles implements ProfileCommandHandler.Profiles {
        final Calls calls = new Calls();
        volatile boolean fail;
        volatile ProfileDeleteRestrictedException restricted;

        private <T> CompletionStage<T> maybeFail(T value) {
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletionStage<PermissionProfileRepository.ProfileView> create(
                UUID owner, String name) {
            calls.ops.add("create:" + name);
            return maybeFail(PROFILE);
        }

        @Override
        public CompletionStage<List<PermissionProfileRepository.ProfileView>> list(UUID owner) {
            calls.ops.add("list");
            return maybeFail(List.of(PROFILE));
        }

        @Override
        public CompletionStage<PermissionProfileRepository.ProfileView> resolve(
                UUID owner, String ref) {
            calls.ops.add("resolve:" + ref);
            if ("missing".equals(ref)) {
                return CompletableFuture.failedFuture(new ProfileRejectedException("profile.unknown"));
            }
            if ("direct".equalsIgnoreCase(ref)) {
                return CompletableFuture.failedFuture(new ProfileRejectedException("profile.reserved"));
            }
            return maybeFail(PROFILE);
        }

        @Override
        public CompletionStage<PermissionProfileRepository.EntryOutcome> setEntry(
                UUID owner, UUID profileId, ProtectionActionType action, PermissionState state) {
            calls.ops.add("set:" + action + ":" + state);
            return maybeFail(new PermissionProfileRepository.EntryOutcome(
                    profileId, action, PermissionState.INHERIT, state));
        }

        @Override
        public CompletionStage<Void> delete(UUID owner, UUID profileId) {
            calls.ops.add("delete");
            if (restricted != null) {
                return CompletableFuture.failedFuture(restricted);
            }
            return maybeFail(null);
        }

        @Override
        public CompletionStage<PermissionProfileRepository.ForceDeleteOutcome> forceDelete(
                UUID owner, UUID profileId) {
            calls.ops.add("force");
            return maybeFail(new PermissionProfileRepository.ForceDeleteOutcome(
                    profileId, PROFILE.displayName(), PROFILE.nameKey(), List.of(
                            new PermissionProfileRepository.AffectedBinding(
                                    "LAND", UUID.randomUUID(), null, "PLAYER", UUID.randomUUID()))));
        }
    }

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final Map<String, Map<String, Object>> vars = new HashMap<>();

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.put(messageKey, Map.copyOf(vs));
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

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private ProfileCommandHandler handler(FakeProfiles profiles) {
        return new ProfileCommandHandler(profiles);
    }

    @Test
    void createListSetDeleteSucceedWithOwnKeys() {
        FakeProfiles profiles = new FakeProfiles();
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "create", "Builders"}, sink);
        assertEquals(List.of("command.land.profile.created"), sink.keys);
        assertEquals("Builders", sink.vars.get("command.land.profile.created").get("profile"));

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "list"}, sink);
        assertEquals(List.of("command.land.profile.listed"), sink.keys);
        assertTrue(sink.vars.get("command.land.profile.listed").get("profiles").toString()
                .contains("Builders"));

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "set", "Builders", "ENTRY", "ALLOW"}, sink);
        assertEquals(List.of("command.land.profile.set"), sink.keys);

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "set", "Builders", "ENTRY", "INHERIT"}, sink);
        assertEquals(List.of("command.land.profile.set"), sink.keys);

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "delete", "Builders"}, sink);
        assertEquals(List.of("command.land.profile.deleted"), sink.keys);

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "delete", "Builders", "--force"}, sink);
        assertEquals(List.of("command.land.profile.force_deleted"), sink.keys);
        assertTrue(sink.vars.get("command.land.profile.force_deleted").get("affected").toString()
                .contains("land "));
    }

    @Test
    void emptyListUsesEmptyKey() {
        FakeProfiles profiles = new FakeProfiles() {
            @Override
            public CompletionStage<List<PermissionProfileRepository.ProfileView>> list(UUID owner) {
                calls.ops.add("list");
                return CompletableFuture.completedFuture(List.of());
            }
        };
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "list"}, sink);
        assertEquals(List.of("command.land.profile.listed_empty"), sink.keys);
    }

    @Test
    void restrictedDeleteNamesAffectedBindings() {
        FakeProfiles profiles = new FakeProfiles();
        UUID landId = UUID.randomUUID();
        profiles.restricted = new ProfileDeleteRestrictedException(List.of(
                new PermissionProfileRepository.AffectedBinding(
                        "LAND", landId, null, "PLAYER", UUID.randomUUID())));
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "delete", "Builders"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.restricted",
                sink.vars.get("command.land.profile.failed").get("reason"));
        assertTrue(sink.vars.get("command.land.profile.failed").get("affected").toString()
                .contains(landId.toString()));
    }

    @Test
    void unknownProfileFailsClosed() {
        FakeProfiles profiles = new FakeProfiles();
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "set", "missing", "ENTRY", "ALLOW"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.unknown", sink.vars.get("command.land.profile.failed").get("reason"));
        assertTrue(profiles.calls.ops.stream().noneMatch(op -> op.startsWith("set:")));
    }

    @Test
    void directProfileFailsClosedWithReservedReason() {
        FakeProfiles profiles = new FakeProfiles();
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "set", "direct", "ENTRY", "ALLOW"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.reserved", sink.vars.get("command.land.profile.failed").get("reason"));
        assertTrue(profiles.calls.ops.stream().noneMatch(op -> op.startsWith("set:")));

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "delete", "direct"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.reserved", sink.vars.get("command.land.profile.failed").get("reason"));
    }

    @Test
    void consoleMissingArgBadEnumFailClosed() {
        FakeProfiles profiles = new FakeProfiles();
        CapturingSink sink = new CapturingSink();
        handler(profiles).handle(console(), new String[] {"profile", "list"}, sink);
        assertEquals(List.of("command.land.profile.console"), sink.keys);
        assertTrue(profiles.calls.ops.isEmpty());

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile"}, sink);
        assertEquals(List.of("command.land.profile.usage"), sink.keys);

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "bogus"}, sink);
        assertEquals(List.of("command.land.profile.usage"), sink.keys);

        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR),
                new String[] {"profile", "set", "Builders", "ENTRY"}, sink);
        assertEquals(List.of("command.land.profile.usage"), sink.keys);
        assertTrue(profiles.calls.ops.stream().noneMatch(op -> op.startsWith("set:")));

        for (String badAction : List.of("NOPE", "EVERYONE", "*", "PISTON_MOVE")) {
            sink = new CapturingSink();
            handler(profiles).handle(player(ACTOR),
                    new String[] {"profile", "set", "Builders", badAction, "ALLOW"}, sink);
            assertEquals(List.of("command.land.profile.failed"), sink.keys,
                    badAction + " must fail closed");
        }
        for (String badState : List.of("YES", "TRUE", "MAYBE")) {
            sink = new CapturingSink();
            handler(profiles).handle(player(ACTOR),
                    new String[] {"profile", "set", "Builders", "ENTRY", badState}, sink);
            assertEquals(List.of("command.land.profile.failed"), sink.keys,
                    badState + " must fail closed");
        }
        assertTrue(profiles.calls.ops.stream().noneMatch(op -> op.startsWith("set:")));
    }

    @Test
    void nullSeamsAndMutationFailureReplyFailedWithoutThrowing() {
        CapturingSink sink = new CapturingSink();
        new ProfileCommandHandler(null)
                .handle(player(ACTOR), new String[] {"profile", "list"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.unavailable",
                sink.vars.get("command.land.profile.failed").get("reason"));

        FakeProfiles profiles = new FakeProfiles();
        profiles.fail = true;
        sink = new CapturingSink();
        handler(profiles).handle(player(ACTOR), new String[] {"profile", "create", "Builders"}, sink);
        assertEquals(List.of("command.land.profile.failed"), sink.keys);
        assertEquals("profile.failed", sink.vars.get("command.land.profile.failed").get("reason"));
    }

    @Test
    void neverAnswersNotYet() {
        FakeProfiles profiles = new FakeProfiles();
        for (String[] args : List.of(
                new String[] {"profile", "create", "Builders"},
                new String[] {"profile", "list"},
                new String[] {"profile", "set", "Builders", "ENTRY", "ALLOW"},
                new String[] {"profile", "delete", "Builders"})) {
            CapturingSink sink = new CapturingSink();
            handler(profiles).handle(player(ACTOR), args, sink);
            assertTrue(sink.keys.stream().noneMatch("command.land.not_yet"::equals));
        }
    }
}
