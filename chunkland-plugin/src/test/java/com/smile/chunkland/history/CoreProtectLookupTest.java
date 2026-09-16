package com.smile.chunkland.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.history.HistoryQuery;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * Reflection mapping contract for the CoreProtect backend: the lookup runs
 * through {@code performLookup} plus {@code parseResult} resolved by name, so
 * the test double below only needs matching method shapes — no CoreProtect
 * types on any classpath. Malformed rows are skipped, attribution is never
 * read, and a shape-mismatched backend throws so the provider degrades to
 * unavailable.
 */
class CoreProtectLookupTest {

    private static final UUID WORLD_ID = UUID.randomUUID();

    /** Parsed-row double with the getter shapes the mapper reads. */
    static final class FakeParsed {
        int x = 12;
        int y = 64;
        int z = -3;
        String type = "STONE";
        long timestamp = 1_700_000_000L;
        String actionString = "placed";
        int actionId = 1;
        String world = "world";
        final AtomicInteger playerReads = new AtomicInteger();

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public String getType() {
            return type;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getActionString() {
            return actionString;
        }

        public int getActionId() {
            return actionId;
        }

        public String worldName() {
            return world;
        }

        public String getPlayer() {
            playerReads.incrementAndGet();
            return "Someone";
        }
    }

    /** Backend double with the lookup shapes the mapper resolves by name. */
    static final class FakeApi {
        final List<String[]> rows = new CopyOnWriteArrayList<>();
        final Map<String, FakeParsed> parsed = new java.util.concurrent.ConcurrentHashMap<>();
        boolean enabled = true;
        int seenTime = -1;
        int seenRadius = -1;
        Location seenLocation;
        int lookups;

        public boolean isEnabled() {
            return enabled;
        }

        public List<String[]> performLookup(int time,
                                            List<String> restrictUsers,
                                            List<String> excludeUsers,
                                            List<Object> restrictBlocks,
                                            List<Object> excludeBlocks,
                                            List<Integer> actions,
                                            int radius,
                                            Location radiusLocation) {
            lookups++;
            seenTime = time;
            seenRadius = radius;
            seenLocation = radiusLocation;
            return new ArrayList<>(rows);
        }

        public FakeParsed parseResult(String[] row) {
            if (row == null || row.length == 0) {
                return null;
            }
            return parsed.get(row[0]);
        }
    }

    private static World worldProxy(UUID uid, String name) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUID" -> uid;
                        case "getName" -> name;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "World-proxy";
                        default -> {
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                yield false;
                            }
                            if (rt == int.class) {
                                yield 0;
                            }
                            if (rt == long.class) {
                                yield 0L;
                            }
                            if (rt == double.class) {
                                yield 0d;
                            }
                            yield null;
                        }
                    };
                });
        return world;
    }

    private static Server serverProxy(World world) {
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getWorld")
                            && args != null && args.length == 1 && args[0] instanceof String) {
                        return world;
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("toString")) {
                        return "Server-proxy";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == double.class) {
                        return 0d;
                    }
                    return null;
                });
    }

    private static HistoryQuery query(int radius, int seconds, int max) {
        return HistoryQuery.bounded(WORLD_ID, "world", 12, 64, -3, radius, seconds, max);
    }

    private static void addRow(FakeApi api, String key, FakeParsed parsed) {
        api.rows.add(new String[] {key});
        if (parsed != null) {
            api.parsed.put(key, parsed);
        }
    }

    @Test
    void validRowsMapToBoundedEntries() throws Exception {
        FakeApi api = new FakeApi();
        addRow(api, "a", new FakeParsed());
        FakeParsed second = new FakeParsed();
        second.x = 13;
        addRow(api, "b", second);
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));

        CoreProtectHistoryProvider.LookupOutcome outcome =
                CoreProtectLookup.fetch(api, server, query(8, 3600, 5));

        assertEquals(2, outcome.entries().size());
        assertFalse(outcome.truncated());
        assertEquals("12,64,-3 placed STONE", outcome.entries().get(0).describe());
        assertEquals(3600, api.seenTime);
        assertEquals(8, api.seenRadius);
        assertEquals(12, api.seenLocation.getBlockX());
    }

    @Test
    void overflowMarksTruncatedAtLimit() throws Exception {
        FakeApi api = new FakeApi();
        for (int i = 0; i < 7; i++) {
            FakeParsed parsed = new FakeParsed();
            parsed.x = i;
            addRow(api, "row-" + i, parsed);
        }
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));

        CoreProtectHistoryProvider.LookupOutcome outcome =
                CoreProtectLookup.fetch(api, server, query(8, 60, 5));

        assertEquals(5, outcome.entries().size());
        assertTrue(outcome.truncated());
    }

    @Test
    void malformedRowsAreSkipped() throws Exception {
        FakeApi api = new FakeApi();
        addRow(api, "good", new FakeParsed());
        api.rows.add(null);
        api.rows.add(new String[0]);
        addRow(api, "unparsed", null);
        FakeParsed blankType = new FakeParsed();
        blankType.type = "  ";
        addRow(api, "blank-type", blankType);
        FakeParsed negativeTime = new FakeParsed();
        negativeTime.timestamp = -1L;
        addRow(api, "negative-time", negativeTime);
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));

        CoreProtectHistoryProvider.LookupOutcome outcome =
                CoreProtectLookup.fetch(api, server, query(8, 60, 5));

        assertEquals(1, outcome.entries().size());
        assertFalse(outcome.truncated());
    }

    @Test
    void crossWorldRowsAreSkipped() throws Exception {
        FakeApi api = new FakeApi();
        FakeParsed foreign = new FakeParsed();
        foreign.world = "other_world";
        addRow(api, "foreign", foreign);
        addRow(api, "local", new FakeParsed());
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));

        CoreProtectHistoryProvider.LookupOutcome outcome =
                CoreProtectLookup.fetch(api, server, query(8, 60, 5));

        assertEquals(1, outcome.entries().size());
    }

    @Test
    void attributionIsNeverRead() throws Exception {
        FakeApi api = new FakeApi();
        FakeParsed parsed = new FakeParsed();
        addRow(api, "a", parsed);
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));

        CoreProtectLookup.fetch(api, server, query(8, 60, 5));

        assertEquals(0, parsed.playerReads.get());
    }

    @Test
    void missingWorldYieldsEmptyWithoutLookup() throws Exception {
        FakeApi api = new FakeApi();
        addRow(api, "a", new FakeParsed());

        CoreProtectHistoryProvider.LookupOutcome outcome =
                CoreProtectLookup.fetch(api, serverProxy(null), query(8, 60, 5));

        assertTrue(outcome.entries().isEmpty());
        assertFalse(outcome.truncated());
        assertEquals(0, api.lookups);
    }

    @Test
    void disabledBackendThrows() {
        FakeApi api = new FakeApi();
        api.enabled = false;
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));
        assertThrows(IllegalStateException.class,
                () -> CoreProtectLookup.fetch(api, server, query(8, 60, 5)));
    }

    @Test
    void shapeMismatchedBackendThrows() {
        Server server = serverProxy(worldProxy(WORLD_ID, "world"));
        assertThrows(IllegalStateException.class,
                () -> CoreProtectLookup.fetch(new Object(), server, query(8, 60, 5)));
    }
}
