package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.persistence.BindingRejectedException;
import com.smile.chunkland.persistence.GroupRejectedException;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.ProfileRejectedException;
import com.smile.chunkland.persistence.SubjectGroupRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * {@code /land binding <bind|unbind>} input handling: player-only entry,
 * usage, reserved subjects, owner-namespace resolution, land and subland
 * scope routing, and created/updated/deleted replies without side effects
 * on any refusal path.
 */
class BindingCommandHandlerTest {

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

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "getName" -> "Console";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Console-proxy";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }

    private record Calls(
            AtomicBoolean mutated,
            AtomicReference<LandBindingRepository.Subject> subject,
            AtomicReference<UUID> profile,
            AtomicReference<SubLandId> subland) {
    }

    private static Calls calls() {
        return new Calls(new AtomicBoolean(false), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
    }

    private static BindingCommandHandler.Bindings bindings(Calls calls, boolean replaced) {
        UUID profileId = UUID.randomUUID();
        return new BindingCommandHandler.Bindings() {
            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject,
                    UUID profile) {
                calls.mutated().set(true);
                calls.subject().set(subject);
                calls.profile().set(profile);
                calls.subland().set(null);
                return CompletableFuture.completedFuture(new LandBindingRepository.BindOutcome(
                        landId.value(), null, subject, profile, replaced));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject) {
                calls.mutated().set(true);
                calls.subject().set(subject);
                return CompletableFuture.completedFuture(new LandBindingRepository.UnbindOutcome(
                        landId.value(), null, subject, profileId));
            }

            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject,
                    UUID profile) {
                calls.mutated().set(true);
                calls.subject().set(subject);
                calls.profile().set(profile);
                calls.subland().set(sublandId);
                return CompletableFuture.completedFuture(new LandBindingRepository.BindOutcome(
                        UUID.randomUUID(), sublandId.value(), subject, profile, replaced));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject) {
                calls.mutated().set(true);
                calls.subject().set(subject);
                calls.subland().set(sublandId);
                return CompletableFuture.completedFuture(new LandBindingRepository.UnbindOutcome(
                        UUID.randomUUID(), sublandId.value(), subject, profileId));
            }
        };
    }

    private static BindingCommandHandler.Groups groups(UUID groupId) {
        UUID owner = UUID.randomUUID();
        return (o, ref) -> CompletableFuture.completedFuture(
                new SubjectGroupRepository.GroupView(groupId, owner, "Crew", "crew",
                        System.currentTimeMillis(), java.util.Set.of()));
    }

    private static BindingCommandHandler.Profiles profiles(UUID profileId) {
        UUID owner = UUID.randomUUID();
        return (o, ref) -> CompletableFuture.completedFuture(
                new PermissionProfileRepository.ProfileView(profileId, owner, "Crew", "crew",
                        System.currentTimeMillis(), Map.of()));
    }

    private static BindingCommandHandler handler(Calls calls, UUID groupId, UUID profileId,
            LandId land, SubLandId subland, Function<String, Optional<UUID>> playerIds,
            boolean replaced) {
        return new BindingCommandHandler(bindings(calls, replaced),
                groupId == null ? null : groups(groupId),
                profileId == null ? null : profiles(profileId),
                sender -> land == null ? Optional.empty() : Optional.of(land),
                (sender, landId) -> subland == null ? Optional.empty() : Optional.of(subland),
                playerIds);
    }

    @Test
    void consoleSenderIsRefusedWithoutMutation() {
        Calls calls = calls();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(console(), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.console"), sink.keys);
        assertFalse(calls.mutated().get());
    }

    @Test
    void missingVerbOrArgsRepliesUsageWithoutMutation() {
        Calls calls = calls();
        UUID actor = UUID.randomUUID();
        BindingCommandHandler handler = handler(calls, UUID.randomUUID(), UUID.randomUUID(),
                new LandId(UUID.randomUUID()), null, name -> Optional.empty(), false);

        CapturingSink first = new CapturingSink();
        handler.handle(player(actor), new String[] {"binding"}, first);
        CapturingSink second = new CapturingSink();
        handler.handle(player(actor),
                new String[] {"binding", "bind", "player", UUID.randomUUID().toString()}, second);

        assertEquals(List.of("command.land.binding.usage"), first.keys);
        assertEquals(List.of("command.land.binding.usage"), second.keys);
        assertFalse(calls.mutated().get());
    }

    @Test
    void invalidKindIsRefusedWithoutMutation() {
        Calls calls = calls();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "everyone",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.invalid", sink.vars.get(0).get("reason"));
        assertFalse(calls.mutated().get());
    }

    @Test
    void reservedPlayerTargetsNeverResolve() {
        Calls calls = calls();
        UUID actor = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        for (String reserved : List.of("EVERYONE", "*", "group(Crew)")) {
            CapturingSink sink = new CapturingSink();
            handler(calls, UUID.randomUUID(), UUID.randomUUID(), land, null,
                    name -> Optional.of(UUID.randomUUID()), false)
                    .handle(player(actor), new String[] {"binding", "bind", "player",
                            reserved, "crew"}, sink);
            assertEquals(List.of("command.land.binding.failed"), sink.keys, reserved);
            assertEquals("binding.invalid", sink.vars.get(0).get("reason"), reserved);
        }
        assertFalse(calls.mutated().get());
    }

    @Test
    void unknownLandFailsClosedWithoutMutation() {
        Calls calls = calls();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), null, null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.unknown_land", sink.vars.get(0).get("reason"));
        assertFalse(calls.mutated().get());
    }

    @Test
    void playerBindByUuidRepliesCreated() {
        Calls calls = calls();
        UUID target = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), profileId, new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        target.toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.created"), sink.keys);
        assertTrue(calls.mutated().get());
        assertEquals(LandBindingRepository.SubjectKind.PLAYER, calls.subject().get().kind());
        assertEquals(target, calls.subject().get().id());
        assertEquals(profileId, calls.profile().get());
        assertEquals(null, calls.subland().get());
    }

    @Test
    void playerBindByOnlineNameResolvesThroughLookup() {
        Calls calls = calls();
        UUID target = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> name.equals("Friend") ? Optional.of(target) : Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        "Friend", "crew"}, sink);

        assertEquals(List.of("command.land.binding.created"), sink.keys);
        assertEquals(target, calls.subject().get().id());
    }

    @Test
    void replaceRepliesUpdated() {
        Calls calls = calls();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), true)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.updated"), sink.keys);
    }

    @Test
    void unbindRepliesDeleted() {
        Calls calls = calls();
        UUID target = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        handler(calls, null, null, new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "unbind", "player",
                        target.toString()}, sink);

        assertEquals(List.of("command.land.binding.deleted"), sink.keys);
        assertEquals(target, calls.subject().get().id());
    }

    @Test
    void groupBindResolvesNameInOwnerNamespace() {
        Calls calls = calls();
        UUID groupId = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        handler(calls, groupId, UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "group",
                        "Crew", "crew-profile"}, sink);

        assertEquals(List.of("command.land.binding.created"), sink.keys);
        assertEquals(LandBindingRepository.SubjectKind.GROUP, calls.subject().get().kind());
        assertEquals(groupId, calls.subject().get().id());
    }

    @Test
    void unknownGroupPassesReasonThroughWithoutMutation() {
        Calls calls = calls();
        BindingCommandHandler handler = new BindingCommandHandler(bindings(calls, false),
                (owner, ref) -> CompletableFuture.failedFuture(
                        new GroupRejectedException("group.unknown")),
                profiles(UUID.randomUUID()),
                sender -> Optional.of(new LandId(UUID.randomUUID())),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty());
        CapturingSink sink = new CapturingSink();
        handler.handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "group",
                "Nobody", "crew"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("group.unknown", sink.vars.get(0).get("reason"));
        assertFalse(calls.mutated().get());
    }

    @Test
    void unknownProfilePassesReasonThroughWithoutMutation() {
        Calls calls = calls();
        BindingCommandHandler handler = new BindingCommandHandler(bindings(calls, false),
                groups(UUID.randomUUID()),
                (owner, ref) -> CompletableFuture.failedFuture(
                        new ProfileRejectedException("profile.unknown")),
                sender -> Optional.of(new LandId(UUID.randomUUID())),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty());
        CapturingSink sink = new CapturingSink();
        handler.handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                UUID.randomUUID().toString(), "Nobody"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("profile.unknown", sink.vars.get(0).get("reason"));
        assertFalse(calls.mutated().get());
    }

    @Test
    void durableRejectionPassesReasonThroughWithoutMutation() {
        Calls calls = calls();
        BindingCommandHandler.Bindings rejecting = new BindingCommandHandler.Bindings() {
            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject,
                    UUID profile) {
                return CompletableFuture.failedFuture(
                        new BindingRejectedException("binding.unknown"));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindLand(
                    UUID owner, LandId landId, LandBindingRepository.Subject subject) {
                return CompletableFuture.failedFuture(
                        new BindingRejectedException("binding.unknown"));
            }

            @Override
            public CompletionStage<LandBindingRepository.BindOutcome> bindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject,
                    UUID profile) {
                return CompletableFuture.failedFuture(
                        new BindingRejectedException("binding.unknown"));
            }

            @Override
            public CompletionStage<LandBindingRepository.UnbindOutcome> unbindSubland(
                    UUID owner, SubLandId sublandId, LandBindingRepository.Subject subject) {
                return CompletableFuture.failedFuture(
                        new BindingRejectedException("binding.unknown"));
            }
        };
        BindingCommandHandler handler = new BindingCommandHandler(rejecting,
                groups(UUID.randomUUID()), profiles(UUID.randomUUID()),
                sender -> Optional.of(new LandId(UUID.randomUUID())),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty());
        CapturingSink sink = new CapturingSink();
        handler.handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.unknown", sink.vars.get(0).get("reason"));
    }

    @Test
    void sublandFlagRoutesToSublandScope() {
        Calls calls = calls();
        SubLandId subland = new SubLandId(UUID.randomUUID());
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()),
                subland, name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew", "--in-subland"}, sink);

        assertEquals(List.of("command.land.binding.created"), sink.keys);
        assertEquals(subland, calls.subland().get());
    }

    @Test
    void sublandFlagWithoutCoveringSublandFailsClosed() {
        Calls calls = calls();
        CapturingSink sink = new CapturingSink();
        handler(calls, UUID.randomUUID(), UUID.randomUUID(), new LandId(UUID.randomUUID()), null,
                name -> Optional.empty(), false)
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew", "--in-subland"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.unknown_land", sink.vars.get(0).get("reason"));
        assertFalse(calls.mutated().get());
    }

    @Test
    void unwiredMutationRepliesUnavailableWithoutSideEffects() {
        CapturingSink sink = new CapturingSink();
        new BindingCommandHandler(null, groups(UUID.randomUUID()), profiles(UUID.randomUUID()),
                sender -> Optional.of(new LandId(UUID.randomUUID())),
                (sender, landId) -> Optional.empty(),
                name -> Optional.empty())
                .handle(player(UUID.randomUUID()), new String[] {"binding", "bind", "player",
                        UUID.randomUUID().toString(), "crew"}, sink);

        assertEquals(List.of("command.land.binding.failed"), sink.keys);
        assertEquals("binding.unavailable", sink.vars.get(0).get("reason"));
    }
}
