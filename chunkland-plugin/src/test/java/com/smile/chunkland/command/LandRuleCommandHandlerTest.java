package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.persistence.LandRuleConflictException;
import com.smile.chunkland.persistence.StaleAuthorisationException;
import com.smile.chunkland.rule.LandRuleView;
import com.smile.chunkland.rule.RulePublishException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Rule command handler: {@code /land rule} lists every rule with its
 * explicit, effective and source values, {@code /land rule <rule>} shows
 * one, and {@code /land rule <rule> <ALLOW|DENY|INHERIT>} persists through
 * the conditional mutation with the observed baseline pinned. Every
 * unresolvable input fails closed with zero side effects, and a committed
 * but unpublished write answers distinctly from a write that never
 * happened.
 */
class LandRuleCommandHandlerTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final LandId LAND = new LandId(UUID.randomUUID());

    private record Call(UUID actor, LandId land, LandRuleType rule,
            PermissionState expected, long revision, PermissionState state) {
    }

    private static final class FakeMutation implements LandRuleCommandHandler.RuleMutation {
        final CopyOnWriteArrayList<Call> calls = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public CompletionStage<Void> apply(UUID actor, LandId landId, LandRuleType rule,
                PermissionState expectedCurrent, long expectedRevision, PermissionState state) {
            calls.add(new Call(actor, landId, rule, expectedCurrent, expectedRevision, state));
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
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

    private static LandRuleView view(LandRuleType rule, PermissionState explicit,
            PermissionState effective, LandRuleView.Source source) {
        return new LandRuleView(rule, explicit, effective, source);
    }

    private static LandRuleCommandHandler handler(FakeMutation mutation, boolean landKnown) {
        return handler(mutation, landKnown,
                (landId, rule) -> Optional.of(
                        new LandRuleCommandHandler.Baseline(PermissionState.INHERIT, 7L)),
                landId -> Optional.of(List.of(view(LandRuleType.PVP,
                        PermissionState.INHERIT, PermissionState.DENY,
                        LandRuleView.Source.BUILT_IN))));
    }

    private static LandRuleCommandHandler handler(FakeMutation mutation, boolean landKnown,
            LandRuleCommandHandler.BaselineSource baselines,
            LandRuleCommandHandler.ViewSource views) {
        return new LandRuleCommandHandler(
                sender -> landKnown ? Optional.of(LAND) : Optional.empty(),
                mutation, baselines, views);
    }

    @ParameterizedTest
    @EnumSource(LandRuleType.class)
    void everyRuleAcceptsAllowDenyAndInherit(LandRuleType rule) {
        for (PermissionState state : PermissionState.values()) {
            FakeMutation mutation = new FakeMutation();
            CapturingSink sink = new CapturingSink();
            handler(mutation, true).handle(player(ACTOR),
                    new String[] {"rule", rule.name(), state.name()}, sink);
            assertEquals(List.of("command.land.rule.success"), sink.keys,
                    rule + " " + state + " must succeed");
            assertEquals(1, mutation.calls.size());
            Call call = mutation.calls.get(0);
            assertEquals(ACTOR, call.actor());
            assertEquals(LAND, call.land());
            assertEquals(rule, call.rule());
            assertEquals(PermissionState.INHERIT, call.expected());
            assertEquals(7L, call.revision());
            assertEquals(state, call.state());
            assertEquals(rule.name(), sink.vars.get("command.land.rule.success").get("action"));
            assertEquals(state.name(), sink.vars.get("command.land.rule.success").get("state"));
        }
    }

    @Test
    void ruleNamesParseCaseInsensitively() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"rule", "pvp", "deny"}, sink);
        assertEquals(List.of("command.land.rule.success"), sink.keys);
        assertEquals(LandRuleType.PVP, mutation.calls.get(0).rule());
        assertEquals(PermissionState.DENY, mutation.calls.get(0).state());
    }

    @Test
    void queryListsEveryRuleWithExplicitEffectiveAndSource() {
        List<LandRuleView> rows = new ArrayList<>();
        for (LandRuleType rule : LandRuleType.values()) {
            rows.add(view(rule, PermissionState.INHERIT, PermissionState.DENY,
                    LandRuleView.Source.BUILT_IN));
        }
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true,
                (landId, rule) -> Optional.of(
                        new LandRuleCommandHandler.Baseline(PermissionState.INHERIT, 7L)),
                landId -> Optional.of(List.copyOf(rows)))
                .handle(player(ACTOR), new String[] {"rule"}, sink);
        assertEquals(11, sink.keys.size(), "one line per rule, got: " + sink.keys);
        for (String key : sink.keys) {
            assertEquals("command.land.rule.line", key);
        }
        assertTrue(mutation.calls.isEmpty(), "a query must never write");
        Map<String, Object> first = sink.vars.get("command.land.rule.line");
        assertTrue(first.containsKey("action"), "line must name the rule");
        assertTrue(first.containsKey("state"), "line must carry the explicit value");
        assertTrue(first.containsKey("outcome"), "line must carry the effective value");
        assertTrue(first.containsKey("layer"), "line must carry the source layer");
    }

    @Test
    void querySingleRuleShowsOneLine() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        List<LandRuleView> rows = List.of(
                view(LandRuleType.FIRE_SPREAD, PermissionState.ALLOW, PermissionState.ALLOW,
                        LandRuleView.Source.LAND),
                view(LandRuleType.PVP, PermissionState.INHERIT, PermissionState.DENY,
                        LandRuleView.Source.BUILT_IN));
        handler(mutation, true,
                (landId, rule) -> Optional.of(
                        new LandRuleCommandHandler.Baseline(PermissionState.INHERIT, 7L)),
                landId -> Optional.of(rows))
                .handle(player(ACTOR), new String[] {"rule", "fire_spread"}, sink);
        assertEquals(List.of("command.land.rule.line"), sink.keys);
        assertEquals("FIRE_SPREAD", sink.vars.get("command.land.rule.line").get("action"));
        assertEquals("ALLOW", sink.vars.get("command.land.rule.line").get("state"));
        assertEquals("ALLOW", sink.vars.get("command.land.rule.line").get("outcome"));
        assertEquals("LAND", sink.vars.get("command.land.rule.line").get("layer"));
        assertTrue(mutation.calls.isEmpty(), "a query must never write");
    }

    @Test
    void unavailableViewsAnswerUnavailableWithoutWriting() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true,
                (landId, rule) -> Optional.of(
                        new LandRuleCommandHandler.Baseline(PermissionState.INHERIT, 7L)),
                landId -> Optional.empty())
                .handle(player(ACTOR), new String[] {"rule"}, sink);
        assertEquals(List.of("command.land.rule.unavailable"), sink.keys);
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void consoleUsageUnknownRuleAndUnknownLandFailClosed() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(console(),
                new String[] {"rule", "PVP", "ALLOW"}, sink);
        assertEquals(List.of("command.land.rule.console"), sink.keys);
        assertTrue(mutation.calls.isEmpty());

        sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR), new String[] {"rule", "PVP",
                "ALLOW", "EXTRA"}, sink);
        assertEquals(List.of("command.land.rule.usage"), sink.keys);
        assertTrue(mutation.calls.isEmpty());

        sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"rule", "PVP", "SOMETIMES"}, sink);
        assertEquals(List.of("command.land.rule.usage"), sink.keys);
        assertTrue(mutation.calls.isEmpty());

        sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"rule", "FLY_FOREVER", "ALLOW"}, sink);
        assertEquals(List.of("command.land.rule.failed"), sink.keys);
        assertEquals("rule.unknown_rule",
                sink.vars.get("command.land.rule.failed").get("reason"));
        assertTrue(mutation.calls.isEmpty());

        sink = new CapturingSink();
        handler(mutation, false).handle(player(ACTOR),
                new String[] {"rule", "PVP", "ALLOW"}, sink);
        assertEquals(List.of("command.land.rule.failed"), sink.keys);
        assertEquals("rule.unknown_land",
                sink.vars.get("command.land.rule.failed").get("reason"));
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void unreadableBaselineFailsClosedWithoutWriting() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true, (landId, rule) -> Optional.empty(),
                landId -> Optional.of(List.of()))
                .handle(player(ACTOR), new String[] {"rule", "PVP", "DENY"}, sink);
        assertEquals(List.of("command.land.rule.failed"), sink.keys);
        assertEquals("rule.failed", sink.vars.get("command.land.rule.failed").get("reason"));
        assertTrue(mutation.calls.isEmpty());
    }

    @Test
    void staleExpectationAnswersStaleWithoutWriting() {
        for (RuntimeException stale : List.of(
                new LandRuleConflictException("changed"),
                new StaleAuthorisationException("lapsed"))) {
            FakeMutation mutation = new FakeMutation();
            mutation.failure = stale;
            CapturingSink sink = new CapturingSink();
            handler(mutation, true).handle(player(ACTOR),
                    new String[] {"rule", "PVP", "DENY"}, sink);
            assertEquals(List.of("command.land.rule.failed"), sink.keys,
                    stale.getClass().getSimpleName() + " must fail closed");
            assertEquals("rule.stale",
                    sink.vars.get("command.land.rule.failed").get("reason"));
        }
    }

    @Test
    void committedButUnpublishedWriteAnswersDegradedDistinctly() {
        FakeMutation mutation = new FakeMutation();
        mutation.failure = new RulePublishException(ACTOR, LAND, LandRuleType.PVP,
                PermissionState.INHERIT, PermissionState.DENY,
                new IllegalStateException("publish boom"));
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"rule", "PVP", "DENY"}, sink);
        assertEquals(List.of("command.land.rule.degraded"), sink.keys,
                "a committed but unpublished write must not read as a plain failure");
        assertEquals("rule.publish_failed",
                sink.vars.get("command.land.rule.degraded").get("reason"));
        assertEquals("PVP", sink.vars.get("command.land.rule.degraded").get("action"));
    }

    @Test
    void consecutiveWritesPinFreshBaselines() {
        FakeMutation mutation = new FakeMutation();
        List<LandRuleCommandHandler.Baseline> baselines = new CopyOnWriteArrayList<>(List.of(
                new LandRuleCommandHandler.Baseline(PermissionState.INHERIT, 7L),
                new LandRuleCommandHandler.Baseline(PermissionState.ALLOW, 8L)));
        CapturingSink sink = new CapturingSink();
        LandRuleCommandHandler handler = handler(mutation, true,
                (landId, rule) -> Optional.of(baselines.remove(0)),
                landId -> Optional.of(List.of()));
        handler.handle(player(ACTOR), new String[] {"rule", "PVP", "ALLOW"}, sink);
        handler.handle(player(ACTOR), new String[] {"rule", "PVP", "INHERIT"}, sink);
        assertEquals(2, mutation.calls.size());
        assertEquals(new Call(ACTOR, LAND, LandRuleType.PVP,
                PermissionState.INHERIT, 7L, PermissionState.ALLOW), mutation.calls.get(0));
        assertEquals(new Call(ACTOR, LAND, LandRuleType.PVP,
                PermissionState.ALLOW, 8L, PermissionState.INHERIT), mutation.calls.get(1));
    }

    @Test
    void inheritOnAnUnsetRuleStillWrites() {
        FakeMutation mutation = new FakeMutation();
        CapturingSink sink = new CapturingSink();
        handler(mutation, true).handle(player(ACTOR),
                new String[] {"rule", "PVP", "INHERIT"}, sink);
        assertEquals(List.of("command.land.rule.success"), sink.keys);
        assertEquals(1, mutation.calls.size());
        assertEquals(PermissionState.INHERIT, mutation.calls.get(0).state());
    }
}
