package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.persistence.GroupDeleteRestrictedException;
import com.smile.chunkland.persistence.GroupRejectedException;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land group} resolves the sender's own namespace and an online
 * member target, then runs the durable operation exactly once; every
 * unresolvable input fails closed with zero side effects and never answers
 * {@code not_yet}.
 */
class GroupCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final SubjectGroupRepository.GroupView GROUP =
            new SubjectGroupRepository.GroupView(GROUP_ID, ACTOR, "Friends", "friends",
                    System.currentTimeMillis(), Set.of());

    private static final class Calls {
        final CopyOnWriteArrayList<String> ops = new CopyOnWriteArrayList<>();
    }

    private static class FakeGroups implements GroupCommandHandler.Groups {
        final Calls calls = new Calls();
        volatile boolean fail;
        volatile GroupDeleteRestrictedException restricted;

        private <T> CompletionStage<T> maybeFail(T value) {
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected"));
            }
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletionStage<SubjectGroupRepository.GroupView> create(UUID owner, String name) {
            calls.ops.add("create:" + name);
            return maybeFail(GROUP);
        }

        @Override
        public CompletionStage<List<SubjectGroupRepository.GroupView>> list(UUID owner) {
            calls.ops.add("list");
            return maybeFail(List.of(GROUP));
        }

        @Override
        public CompletionStage<SubjectGroupRepository.GroupView> resolve(UUID owner, String ref) {
            calls.ops.add("resolve:" + ref);
            if ("missing".equals(ref)) {
                return CompletableFuture.failedFuture(new GroupRejectedException("group.unknown"));
            }
            return maybeFail(GROUP);
        }

        @Override
        public CompletionStage<SubjectGroupRepository.MembershipOutcome> addMember(
                UUID owner, UUID groupId, UUID member) {
            calls.ops.add("add:" + member);
            return maybeFail(new SubjectGroupRepository.MembershipOutcome(groupId, member, false, true));
        }

        @Override
        public CompletionStage<SubjectGroupRepository.MembershipOutcome> removeMember(
                UUID owner, UUID groupId, UUID member) {
            calls.ops.add("remove:" + member);
            return maybeFail(new SubjectGroupRepository.MembershipOutcome(groupId, member, true, false));
        }

        @Override
        public CompletionStage<Void> delete(UUID owner, UUID groupId) {
            calls.ops.add("delete");
            if (restricted != null) {
                return CompletableFuture.failedFuture(restricted);
            }
            return maybeFail(null);
        }

        @Override
        public CompletionStage<SubjectGroupRepository.ForceDeleteOutcome> forceDelete(
                UUID owner, UUID groupId) {
            calls.ops.add("force");
            return maybeFail(new SubjectGroupRepository.ForceDeleteOutcome(
                    groupId, GROUP.displayName(), GROUP.nameKey(), List.of(
                            new SubjectGroupRepository.AffectedBinding(
                                    "LAND", UUID.randomUUID(), null, UUID.randomUUID()))));
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

    private GroupCommandHandler handler(FakeGroups groups) {
        return new GroupCommandHandler(groups,
                name -> TARGET.toString().equals(name) || "Target".equals(name)
                        ? Optional.of(TARGET) : Optional.empty());
    }

    @Test
    void createListAddRemoveDeleteSucceedWithOwnKeys() {
        FakeGroups groups = new FakeGroups();
        CapturingSink sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "create", "Friends"}, sink);
        assertEquals(List.of("command.land.group.created"), sink.keys);
        assertEquals("Friends", sink.vars.get("command.land.group.created").get("group"));

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "list"}, sink);
        assertEquals(List.of("command.land.group.listed"), sink.keys);
        assertTrue(sink.vars.get("command.land.group.listed").get("groups").toString()
                .contains("Friends"));

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR),
                new String[] {"group", "add", "Friends", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.group.added"), sink.keys);

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR),
                new String[] {"group", "remove", "Friends", "Target"}, sink);
        assertEquals(List.of("command.land.group.removed"), sink.keys);

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "delete", "Friends"}, sink);
        assertEquals(List.of("command.land.group.deleted"), sink.keys);

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR),
                new String[] {"group", "delete", "Friends", "--force"}, sink);
        assertEquals(List.of("command.land.group.force_deleted"), sink.keys);
        assertTrue(sink.vars.get("command.land.group.force_deleted").get("affected").toString()
                .contains("land "));
    }

    @Test
    void emptyListUsesEmptyKey() {
        FakeGroups groups = new FakeGroups() {
            @Override
            public CompletionStage<List<SubjectGroupRepository.GroupView>> list(UUID owner) {
                calls.ops.add("list");
                return CompletableFuture.completedFuture(List.of());
            }
        };
        CapturingSink sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "list"}, sink);
        assertEquals(List.of("command.land.group.listed_empty"), sink.keys);
    }

    @Test
    void restrictedDeleteNamesAffectedBindings() {
        FakeGroups groups = new FakeGroups();
        UUID landId = UUID.randomUUID();
        groups.restricted = new GroupDeleteRestrictedException(List.of(
                new SubjectGroupRepository.AffectedBinding("LAND", landId, null, UUID.randomUUID())));
        CapturingSink sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "delete", "Friends"}, sink);
        assertEquals(List.of("command.land.group.failed"), sink.keys);
        assertEquals("group.restricted",
                sink.vars.get("command.land.group.failed").get("reason"));
        assertTrue(sink.vars.get("command.land.group.failed").get("affected").toString()
                .contains(landId.toString()));
    }

    @Test
    void unknownGroupFailsClosed() {
        FakeGroups groups = new FakeGroups();
        CapturingSink sink = new CapturingSink();
        handler(groups).handle(player(ACTOR),
                new String[] {"group", "add", "missing", TARGET.toString()}, sink);
        assertEquals(List.of("command.land.group.failed"), sink.keys);
        assertEquals("group.unknown", sink.vars.get("command.land.group.failed").get("reason"));
        assertTrue(groups.calls.ops.stream().noneMatch(op -> op.startsWith("add:")));
    }

    @Test
    void consoleMissingArgWildcardUnknownTargetFailClosed() {
        FakeGroups groups = new FakeGroups();
        CapturingSink sink = new CapturingSink();
        handler(groups).handle(console(), new String[] {"group", "list"}, sink);
        assertEquals(List.of("command.land.group.console"), sink.keys);
        assertTrue(groups.calls.ops.isEmpty());

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group"}, sink);
        assertEquals(List.of("command.land.group.usage"), sink.keys);

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "bogus"}, sink);
        assertEquals(List.of("command.land.group.usage"), sink.keys);

        for (String wildcard : List.of("EVERYONE", "everyone", "*", "group(Friends)")) {
            sink = new CapturingSink();
            handler(groups).handle(player(ACTOR),
                    new String[] {"group", "add", "Friends", wildcard}, sink);
            assertEquals(List.of("command.land.group.failed"), sink.keys,
                    wildcard + " must fail closed");
            assertEquals("group.unknown_target",
                    sink.vars.get("command.land.group.failed").get("reason"));
        }
        assertTrue(groups.calls.ops.stream().noneMatch(op -> op.startsWith("add:")));

        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR),
                new String[] {"group", "add", "Friends", "NobodyOnline"}, sink);
        assertEquals(List.of("command.land.group.failed"), sink.keys);
        assertTrue(groups.calls.ops.stream().noneMatch(op -> op.startsWith("add:")));
    }

    @Test
    void nullSeamsAndMutationFailureReplyFailedWithoutThrowing() {
        CapturingSink sink = new CapturingSink();
        new GroupCommandHandler(null, name -> Optional.of(TARGET))
                .handle(player(ACTOR), new String[] {"group", "list"}, sink);
        assertEquals(List.of("command.land.group.failed"), sink.keys);
        assertEquals("group.unavailable",
                sink.vars.get("command.land.group.failed").get("reason"));

        FakeGroups groups = new FakeGroups();
        groups.fail = true;
        sink = new CapturingSink();
        handler(groups).handle(player(ACTOR), new String[] {"group", "create", "Friends"}, sink);
        assertEquals(List.of("command.land.group.failed"), sink.keys);
        assertEquals("group.failed", sink.vars.get("command.land.group.failed").get("reason"));
    }

    @Test
    void neverAnswersNotYet() {
        FakeGroups groups = new FakeGroups();
        for (String[] args : List.of(
                new String[] {"group", "create", "Friends"},
                new String[] {"group", "list"},
                new String[] {"group", "add", "Friends", TARGET.toString()},
                new String[] {"group", "remove", "Friends", TARGET.toString()},
                new String[] {"group", "delete", "Friends"})) {
            CapturingSink sink = new CapturingSink();
            handler(groups).handle(player(ACTOR), args, sink);
            assertTrue(sink.keys.stream().noneMatch("command.land.not_yet"::equals));
        }
    }
}
