package com.smile.chunkland.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.event.RuleChangedEvent;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.event.bukkit.RuleChangedBukkitEvent;
import com.smile.chunkland.persistence.LandAuthorisationRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.protection.LandAuthorisationCache;
import com.smile.chunkland.rule.LandRuleMutationService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RuleChanged path status: the event contract, bus dispatch and Bukkit
 * bridge are fully wired and covered here, and the durable per-land rule
 * writer fires {@link RuleChangedEvent} exactly once after the durable
 * commit plus the runtime publish.
 */
class RuleChangedEventPathTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");

    @TempDir Path tmp;

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
    void durableWriterFiresRuleChangedOnceAfterCommitAndPublish() {
        Path path = tmp.resolve(UUID.randomUUID() + ".db");
        UUID world = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LandId land = new LandId(UUID.randomUUID());
        PublicEventBus bus = new PublicEventBus();
        AtomicInteger apiCalls = new AtomicInteger();
        List<Event> bukkitCalls = new ArrayList<>();
        bus.register(RuleChangedEvent.class, event -> apiCalls.incrementAndGet());
        try (PersistenceStore store = PersistenceStore.open(path)) {
            new SqliteLandRepository(store).save(new LandSnapshot(land, "Home", "home",
                    OwnerRef.player(actor), world, Set.of(new ChunkKey(world, 0, 0)),
                    List.of(), 0, 0, NOW, NOW)).toCompletableFuture().join();
            LandRuleMutationService service = new LandRuleMutationService(
                    new LandAuthorisationRepository(store), new LandAuthorisationCache(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    PublicEvents.create(bus, bukkitCalls::add));

            service.setRule(actor, land, LandRuleType.PVP, PermissionState.DENY)
                    .toCompletableFuture().join();

            assertEquals(1, apiCalls.get(),
                    "one durable rule mutation fires exactly one API event");
            assertEquals(1, bukkitCalls.size(),
                    "one durable rule mutation fires exactly one Bukkit event");
            assertTrue(bukkitCalls.get(0) instanceof RuleChangedBukkitEvent);
        }
    }
}
