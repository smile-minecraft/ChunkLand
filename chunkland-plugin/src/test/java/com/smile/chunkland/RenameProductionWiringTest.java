package com.smile.chunkland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.smile.chunkland.command.LandCommand;
import com.smile.chunkland.command.RenameCommandHandler;
import com.smile.chunkland.command.ReplySink;
import com.smile.chunkland.selection.SelectionStructureRevisionLookup;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Production wiring for {@code /land rename}: the formal handler map must
 * route to the real command handler instead of the not-yet stub, and an
 * unwired slot must fail closed instead of pretending the flow is coming.
 */
class RenameProductionWiringTest {

    private static final class CapturingSink implements ReplySink {
        final List<String> keys = new ArrayList<>();
        final List<Map<String, Object>> vars = new ArrayList<>();
        final CountDownLatch done;

        CapturingSink(int expected) {
            done = new CountDownLatch(expected);
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs) {
            keys.add(messageKey);
            vars.add(Map.copyOf(vs));
            done.countDown();
        }

        @Override
        public void reply(String messageKey, Map<String, Object> vs, Locale localeOverride) {
            reply(messageKey, vs);
        }

        void await() throws InterruptedException {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for reply, got " + keys);
            }
        }
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "hasPermission" -> true;
                        case "getName" -> "Actor";
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "Player-proxy";
                        default -> method.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static Map<String, LandCommand.Handler> handlers(RenameCommandHandler rename) {
        return ChunkLandPlugin.buildLandHandlers(
                null, null, null, SelectionStructureRevisionLookup.unavailable(),
                null, null, null, null, null, null, null, null, null, rename);
    }

    @Test
    void formalHandlerMapRoutesRenameToRealHandler() {
        RenameCommandHandler handler = new RenameCommandHandler(
                sender -> Optional.empty(), null, sender -> false);

        Map<String, LandCommand.Handler> wired = handlers(handler);

        assertSame(handler, wired.get("rename"),
                "formal /land wiring must assemble the real rename handler, not the stub");
    }

    @Test
    void unwiredRenameSlotFailsClosedInsteadOfStub() throws Exception {
        Map<String, LandCommand.Handler> wired = handlers(null);
        CapturingSink sink = new CapturingSink(1);

        wired.get("rename").handle(
                player(UUID.randomUUID()), new String[] {"rename", "Garden"}, sink);
        sink.await();

        assertEquals(1, sink.keys.size());
        assertNotEquals("command.land.not_yet", sink.keys.get(0),
                "an unwired rename slot must fail closed, never fall back to the stub");
        assertEquals("command.land.rename.failed", sink.keys.get(0));
        assertEquals("rename.unavailable", sink.vars.get(0).get("reason"));
    }

    @Test
    void buildLandRenameRejectsPartialWiring() {
        assertNull(ChunkLandPlugin.buildLandRename(null, null, null),
                "partial rename wiring must stay fail-closed");
    }

    @Test
    void renameSlotSurvivesShorterOverloadsAsUnavailable() throws Exception {
        Map<String, LandCommand.Handler> wired = ChunkLandPlugin.buildLandHandlers();
        CapturingSink sink = new CapturingSink(1);

        wired.get("rename").handle(
                player(UUID.randomUUID()), new String[] {"rename", "Garden"}, sink);
        sink.await();

        // The base map keeps the legacy stub for overloads that predate the
        // rename flow; the formal overload above is what production uses.
        assertEquals("command.land.not_yet", sink.keys.get(0));
    }
}
