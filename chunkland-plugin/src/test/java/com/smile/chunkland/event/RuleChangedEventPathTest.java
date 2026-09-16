package com.smile.chunkland.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.event.bukkit.RuleChangedBukkitEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

/**
 * RuleChanged path status: the event contract, bus dispatch and Bukkit
 * bridge are fully wired and covered here, but production currently has no
 * durable per-land rule mutation to fire from — every {@code
 * LandRuleService} is built with empty per-land overrides and rule state
 * comes from config defaults. Fabricating a firing point (or a schemaless
 * mutation) would invent product behaviour, so this test pins the
 * non-applicable state instead:
 *
 * <p>If a durable per-land rule writer is ever added (a new {@code
 * setRule/putRule/saveRule/updateRule} method in main sources), this test
 * fails on purpose: the author must fire {@link RuleChangedEvent} after the
 * durable commit plus the runtime publish and update this guard.
 */
class RuleChangedEventPathTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java/com/smile/chunkland");

    /** Writer-shaped method names for durable per-land rule state. */
    private static final Pattern RULE_WRITER = Pattern.compile(
            "\\b(setRule|putRule|saveRule|updateRule|setLandRule|putLandRule)\\s*\\(");

    private static List<Path> sources() throws IOException {
        if (!Files.isDirectory(MAIN_SRC)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(MAIN_SRC)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    @Test
    void ruleChangedContractDispatchesOnBothChannels() {
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger apiCalls = new AtomicInteger();
        List<Event> bukkitCalls = new ArrayList<>();
        PublicEvents events = PublicEvents.create(bus, bukkitCalls::add);
        bus.register(RuleChangedEvent.class, event -> apiCalls.incrementAndGet());

        LandId land = new LandId(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        events.firePost(new RuleChangedEvent(actor, land, LandRuleType.PVP, null,
                PermissionState.DENY),
                () -> new RuleChangedBukkitEvent(actor, land, LandRuleType.PVP, null,
                        PermissionState.DENY, true));

        assertEquals(1, apiCalls.get());
        assertEquals(1, bukkitCalls.size());
        assertTrue(bukkitCalls.get(0) instanceof RuleChangedBukkitEvent);
        RuleChangedBukkitEvent view = (RuleChangedBukkitEvent) bukkitCalls.get(0);
        assertEquals(land, view.landId());
        assertEquals(LandRuleType.PVP, view.rule());
        assertEquals(PermissionState.DENY, view.current());
    }

    @Test
    void throwingRuleListenerIsIsolated() {
        PublicEventBus bus = new PublicEventBus();
        bus.register(RuleChangedEvent.class, event -> {
            throw new IllegalStateException("broken listener");
        });
        // Must not throw: Post failures are isolated by the bus.
        bus.publish(new RuleChangedEvent(UUID.randomUUID(), new LandId(UUID.randomUUID()),
                LandRuleType.PVP, PermissionState.ALLOW, PermissionState.DENY));
    }

    @Test
    void noDurablePerLandRuleWriterExistsYet() throws IOException {
        List<Path> files = sources();
        assertTrue(!files.isEmpty(), "plugin sources must exist when run from the module dir");
        for (Path file : files) {
            String source = Files.readString(file);
            if (RULE_WRITER.matcher(source).find()) {
                fail("A durable per-land rule writer appeared in " + file.getFileName()
                        + ": wire RuleChangedEvent after commit+publish and update this guard. "
                        + "See " + PermissionChangedEvent.class.getSimpleName()
                        + " emission in LandAuthorisationService for the pattern.");
            }
        }
    }
}
