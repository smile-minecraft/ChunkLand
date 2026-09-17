package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import com.smile.chunkland.api.land.LandId;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Toggle contract for {@code /land bypass on|off}.
 *
 * <p>The bypass node only allows <em>attempting</em> the switch. Every
 * successful switch appends one {@code ADMIN_BYPASS_TOGGLE} audit row first
 * and only then flips the actor-scoped in-memory state; an audit failure,
 * a console sender, a missing node or malformed input changes nothing.
 */
class AdminBypassCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final String BYPASS_NODE = "chunkland.admin.bypass";

    private record Reply(String key, Map<String, Object> vars) {
    }

    private static final class CapturingSink implements ReplySink {
        final List<Reply> replies = new CopyOnWriteArrayList<>();

        @Override public void reply(String messageKey, Map<String, Object> vars) {
            replies.add(new Reply(messageKey, vars));
        }

        @Override public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
            reply(messageKey, vars);
        }
    }

    /** In-memory audit double that observes the bypass state at insert time. */
    private static final class FakeAudits implements AuditRepository {
        final List<AuditEntry> inserted = new CopyOnWriteArrayList<>();
        final List<Boolean> statesAtInsert = new CopyOnWriteArrayList<>();
        final AtomicReference<Boolean> stateAtInsert = new AtomicReference<>();
        final AdminBypassState states;
        final UUID watched;
        final AtomicBoolean failInserts = new AtomicBoolean(false);
        final AtomicBoolean throwInline = new AtomicBoolean(false);

        FakeAudits(AdminBypassState states, UUID watched) {
            this.states = states;
            this.watched = watched;
        }

        @Override public CompletionStage<Long> insert(AuditEntry entry) {
            if (throwInline.get()) {
                throw new IllegalStateException("audit store down");
            }
            if (failInserts.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("audit write failed"));
            }
            boolean seen = states.isOn(watched);
            stateAtInsert.set(seen);
            statesAtInsert.add(seen);
            inserted.add(entry);
            return CompletableFuture.completedFuture((long) inserted.size());
        }

        @Override public CompletionStage<Optional<AuditEntry>> findById(long id) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public CompletionStage<List<AuditEntry>> findByLand(LandId landId, int limit, int offset) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<AuditEntry>> findByAction(String action, int limit) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override public CompletionStage<List<AuditEntry>> findAll(int limit, int offset) {
            return CompletableFuture.completedFuture(List.copyOf(inserted));
        }

        @Override public CompletionStage<List<AuditEntry>> search(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<List<String>> explainSearch(AuditSearchQuery query) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<Integer> purgeOlderThan(Instant cutoff) {
            return CompletableFuture.completedFuture(0);
        }
    }

    private static Player player(UUID uuid, boolean bypassNode) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getUniqueId")) {
                        return uuid;
                    }
                    if (name.equals("hasPermission")) {
                        return BYPASS_NODE.equals(args[0]) && bypassNode;
                    }
                    if (name.equals("getName")) {
                        return "Bypass-admin";
                    }
                    if (name.equals("locale")) {
                        return Locale.US;
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Bypass-admin-proxy";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static CommandSender console() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("hasPermission")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "Console";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("toString")) {
                        return "Console-proxy";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }

    private static AdminBypassCommandHandler handler(FakeAudits audits, AdminBypassState states) {
        return new AdminBypassCommandHandler(() -> audits, () -> NOW, states, PlayerScheduler.direct());
    }

    @Test
    void successfulOnWritesAuditBeforeTheStateBecomesVisible() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "on"}, sink);

        assertEquals(2, audits.inserted.size(), "one toggle must write attempt plus committed terminal");
        AuditEntry entry = audits.inserted.get(0);
        assertEquals("ADMIN_BYPASS_TOGGLE", entry.action());
        assertEquals(actor, entry.actor());
        assertEquals(NOW, entry.timestamp());
        assertNull(entry.landId(), "bypass toggles carry no land");
        assertNotNull(entry.beforeJson());
        assertNotNull(entry.afterJson());
        assertTrue(entry.afterJson().contains("true"), "after must record the enabled state");
        assertNotNull(entry.metadataJson());
        assertTrue(AdminBypassCommandHandler.isAttemptRow(entry));
        AuditEntry terminal = audits.inserted.get(1);
        assertTrue(AdminBypassCommandHandler.isCommittedRow(terminal));
        assertEquals(AdminBypassCommandHandler.attemptIdOf(entry),
                AdminBypassCommandHandler.attemptIdOf(terminal));
        assertEquals(Boolean.FALSE, audits.statesAtInsert.get(0),
                "the attempt row must land before the state flips");
        assertEquals(Boolean.TRUE, audits.statesAtInsert.get(1),
                "the committed terminal lands after the state flips");
        assertTrue(states.isOn(actor), "the state flips only after the audit succeeds");
        assertTrue(AdminBypassCommandHandler.hasCommittedFor(
                AdminBypassCommandHandler.attemptIdOf(entry).orElseThrow(), audits.inserted));
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.on", sink.replies.get(0).key());
    }

    @Test
    void successfulOffWritesAuditAndDisablesImmediately() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        states.setEnabled(actor, true);
        FakeAudits audits = new FakeAudits(states, actor);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "off"}, sink);

        assertEquals(2, audits.inserted.size());
        assertEquals("ADMIN_BYPASS_TOGGLE", audits.inserted.get(0).action());
        assertTrue(audits.inserted.get(0).afterJson().contains("false"));
        assertTrue(AdminBypassCommandHandler.isAttemptRow(audits.inserted.get(0)));
        assertTrue(AdminBypassCommandHandler.isCommittedRow(audits.inserted.get(1)));
        assertEquals(AdminBypassCommandHandler.attemptIdOf(audits.inserted.get(0)),
                AdminBypassCommandHandler.attemptIdOf(audits.inserted.get(1)));
        assertEquals(Boolean.TRUE, audits.statesAtInsert.get(0),
                "the attempt row must land while the old state is still on");
        assertFalse(states.isOn(actor));
        assertEquals("command.land.bypass.off", sink.replies.get(0).key());
    }

    @Test
    void auditFailureChangesNoState() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        audits.failInserts.set(true);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "on"}, sink);

        assertFalse(states.isOn(actor), "a failed audit must not flip the state");
        assertEquals(1, sink.replies.size());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
    }

    @Test
    void auditThrowingInlineChangesNoState() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        audits.throwInline.set(true);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "on"}, sink);

        assertFalse(states.isOn(actor));
        assertTrue(audits.inserted.isEmpty());
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
    }

    @Test
    void consoleIsDeniedWithZeroSideEffects() {
        AdminBypassState states = new AdminBypassState();
        UUID watched = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, watched);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(console(), new String[] {"bypass", "on"}, sink);

        assertTrue(audits.inserted.isEmpty(), "console must not write audit rows");
        assertEquals(0, states.size(), "console must not touch the state");
        assertEquals("command.land.bypass.console", sink.replies.get(0).key());
    }

    @Test
    void missingNodeIsDeniedWithZeroSideEffects() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        CapturingSink sink = new CapturingSink();

        handler(audits, states).handle(player(actor, false), new String[] {"bypass", "on"}, sink);

        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));
        assertEquals("command.land.denied", sink.replies.get(0).key());
    }

    @Test
    void malformedInputStaysOnUsageWithZeroSideEffects() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        List<String[]> badInputs = new ArrayList<>();
        badInputs.add(new String[] {"bypass"});
        badInputs.add(new String[] {"bypass", "ONCE"});
        badInputs.add(new String[] {"bypass", ""});
        badInputs.add(new String[] {"bypass", "on", "extra"});
        badInputs.add(new String[] {"bypass", "yes"});
        for (String[] input : badInputs) {
            CapturingSink sink = new CapturingSink();
            handler(audits, states).handle(player(actor, true), input, sink);
            assertEquals("command.land.bypass.usage", sink.replies.get(0).key(),
                    "input " + String.join("/", input) + " must stay on usage");
        }
        assertTrue(audits.inserted.isEmpty());
        assertFalse(states.isOn(actor));
    }

    @Test
    void toggleIsCaseInsensitiveButExact() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);

        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "ON"}, new CapturingSink());
        assertTrue(states.isOn(actor));
        handler(audits, states).handle(player(actor, true), new String[] {"bypass", "Off"}, new CapturingSink());
        assertFalse(states.isOn(actor));
        assertEquals(4, audits.inserted.size());
    }

    @Test
    void nullAuditSourceFailsClosedWithZeroStateChange() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        CapturingSink sink = new CapturingSink();
        AdminBypassCommandHandler handler =
                new AdminBypassCommandHandler(null, () -> NOW, states, PlayerScheduler.direct());

        handler.handle(player(actor, true), new String[] {"bypass", "on"}, sink);

        assertFalse(states.isOn(actor));
        assertEquals("command.land.bypass.failed", sink.replies.get(0).key());
    }

    @Test
    void repeatedToggleWritesOneAuditPerSwitch() {
        AdminBypassState states = new AdminBypassState();
        UUID actor = UUID.randomUUID();
        FakeAudits audits = new FakeAudits(states, actor);
        AdminBypassCommandHandler handler = handler(audits, states);

        for (int i = 0; i < 4; i++) {
            handler.handle(player(actor, true), new String[] {"bypass", "on"}, new CapturingSink());
            assertTrue(states.isOn(actor));
            handler.handle(player(actor, true), new String[] {"bypass", "off"}, new CapturingSink());
            assertFalse(states.isOn(actor));
        }
        assertEquals(16, audits.inserted.size());
        for (AuditEntry entry : audits.inserted) {
            assertEquals("ADMIN_BYPASS_TOGGLE", entry.action());
            assertEquals(actor, entry.actor());
        }
    }
}
