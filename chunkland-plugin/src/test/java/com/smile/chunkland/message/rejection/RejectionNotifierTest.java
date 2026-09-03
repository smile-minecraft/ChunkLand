package com.smile.chunkland.message.rejection;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.selection.SelectionClock;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class RejectionNotifierTest {

    private static final class FakeClock implements SelectionClock {
        private final AtomicReference<Instant> now = new AtomicReference<>(
                Instant.parse("2026-09-03T00:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advance(Duration delta) {
            now.updateAndGet(t -> t.plus(delta));
        }
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger sends = new AtomicInteger();
        volatile RuntimeException renderFailure;
        volatile RuntimeException sendFailure;
        volatile RejectionNotifier.Sender senderOverride;
        volatile boolean nullSender;

        RejectionNotifier notifier() {
            return notifier(Set.of());
        }

        RejectionNotifier notifier(Set<ProtectionActionType> extraSilent) {
            RejectionNotifier.Sender sender;
            if (nullSender) {
                sender = null;
            } else if (senderOverride != null) {
                sender = senderOverride;
            } else {
                sender = (player, message) -> {
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    sends.incrementAndGet();
                };
            }
            RejectionNotifier.Renderer renderer = (player, action, decision) -> {
                if (renderFailure != null) {
                    throw renderFailure;
                }
                renders.incrementAndGet();
                return Component.text("denied: " + action.name());
            };
            return new RejectionNotifier(sender, renderer,
                    new RejectionCooldown(clock, Duration.ofSeconds(3)), extraSilent);
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId": return id;
                        case "getName": return "TestPlayer";
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "FakePlayer";
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static PermissionDecision denySubject(String reason) {
        return new PermissionDecision(PermissionState.DENY,
                DecisionSource.SUBJECT_PERMISSION, reason);
    }

    private static PermissionDecision denyRule(String reason) {
        return new PermissionDecision(PermissionState.DENY, DecisionSource.LAND_RULE, reason);
    }

    private static PermissionDecision allow() {
        return new PermissionDecision(PermissionState.ALLOW,
                DecisionSource.SUBJECT_PERMISSION, "allowed");
    }

    @Test
    void allowDecisionSendsNothing() {
        Fixture fx = new Fixture();
        boolean sent = fx.notifier().notifyDenied(
                player(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK, allow());
        assertFalse(sent);
        assertEquals(0, fx.renders.get(), "ALLOW must build zero Components");
        assertEquals(0, fx.sends.get(), "ALLOW must send zero messages");
    }

    @Test
    void nullDecisionIsTreatedAsAllow() {
        Fixture fx = new Fixture();
        boolean sent = fx.notifier().notifyDenied(
                player(UUID.randomUUID()), ProtectionActionType.BLOCK_BREAK, null);
        assertFalse(sent);
        assertEquals(0, fx.renders.get());
        assertEquals(0, fx.sends.get());
    }

    @Test
    void landRuleDenyIsSilentByDefault() {
        Fixture fx = new Fixture();
        boolean sent = fx.notifier().notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.PLAYER_DAMAGE_PLAYER, denyRule("pvp off"));
        assertFalse(sent, "LAND_RULE deny must stay silent");
        assertEquals(0, fx.renders.get(), "silent deny must build zero Components");
        assertEquals(0, fx.sends.get());
    }

    @Test
    void subjectDenySendsOnce() {
        Fixture fx = new Fixture();
        boolean sent = fx.notifier().notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access"));
        assertTrue(sent);
        assertEquals(1, fx.renders.get());
        assertEquals(1, fx.sends.get());
    }

    @Test
    void secondDenyWithinCooldownIsSuppressed() {
        Fixture fx = new Fixture();
        RejectionNotifier notifier = fx.notifier();
        Player target = player(UUID.randomUUID());
        assertTrue(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        fx.clock.advance(Duration.ofSeconds(2));
        assertFalse(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        assertEquals(1, fx.renders.get(), "cooldown deny must build zero extra Components");
        assertEquals(1, fx.sends.get());
    }

    @Test
    void denyAfterCooldownExpiryResends() {
        Fixture fx = new Fixture();
        RejectionNotifier notifier = fx.notifier();
        Player target = player(UUID.randomUUID());
        assertTrue(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        fx.clock.advance(Duration.ofSeconds(3));
        assertTrue(notifier.notifyDenied(target, ProtectionActionType.BLOCK_BREAK,
                denySubject("no access")));
        assertEquals(2, fx.renders.get());
        assertEquals(2, fx.sends.get());
    }

    @Test
    void cooldownIsPerPlayerAndAction() {
        Fixture fx = new Fixture();
        RejectionNotifier notifier = fx.notifier();
        assertTrue(notifier.notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access")));
        assertTrue(notifier.notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access")),
                "another player must have its own window");
        assertTrue(notifier.notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.ENTRY, denySubject("no entry")),
                "another action must have its own window");
        assertEquals(3, fx.sends.get());
    }

    @Test
    void extraSilentActionIsSuppressed() {
        Fixture fx = new Fixture();
        RejectionNotifier notifier =
                fx.notifier(EnumSet.of(ProtectionActionType.ENTRY));
        assertTrue(notifier.isSilent(ProtectionActionType.ENTRY));
        assertFalse(notifier.isSilent(ProtectionActionType.BLOCK_BREAK));
        boolean sent = notifier.notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.ENTRY, denySubject("no entry"));
        assertFalse(sent);
        assertEquals(0, fx.renders.get());
        assertEquals(0, fx.sends.get());
    }

    @Test
    void missingPipelineFailClosed() {
        Fixture fx = new Fixture();
        fx.nullSender = true;
        boolean sent = fx.notifier().notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access"));
        assertFalse(sent, "absent pipeline must silently drop the notice");
        assertEquals(0, fx.renders.get(), "absent pipeline must build zero Components");
    }

    @Test
    void rendererFailureFailClosed() {
        Fixture fx = new Fixture();
        fx.renderFailure = new RuntimeException("render boom");
        boolean sent = fx.notifier().notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access"));
        assertFalse(sent, "render failure must not propagate");
        assertEquals(0, fx.sends.get());
    }

    @Test
    void senderFailureFailClosed() {
        Fixture fx = new Fixture();
        fx.sendFailure = new RuntimeException("send boom");
        boolean sent = fx.notifier().notifyDenied(player(UUID.randomUUID()),
                ProtectionActionType.BLOCK_BREAK, denySubject("no access"));
        assertFalse(sent, "send failure must not propagate");
    }

    @Test
    void nullPlayerOrActionFailClosed() {
        Fixture fx = new Fixture();
        RejectionNotifier notifier = fx.notifier();
        assertFalse(notifier.notifyDenied(null,
                ProtectionActionType.BLOCK_BREAK, denySubject("no access")));
        assertFalse(notifier.notifyDenied(player(UUID.randomUUID()), null,
                denySubject("no access")));
        assertEquals(0, fx.renders.get());
        assertEquals(0, fx.sends.get());
    }
}
