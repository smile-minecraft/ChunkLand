package com.smile.chunkland.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.api.rule.LandRuleType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Public event contracts: cancellable Pre semantics, immutable Post
 * snapshots, bounded chunk sets, a dependency-free event package, and a
 * synchronous bus.
 */
class PublicEventContractTest {

    private static final Path EVENT_SRC = Paths.get("src/main/java/com/smile/chunkland/api/event");

    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(org\\.bukkit\\.|org\\.paper\\.|java\\.sql\\.|javax\\.sql\\."
                    + "|net\\.milkbowl\\.|com\\.earth2me\\.|com\\.smile\\.acelib\\.)",
            Pattern.MULTILINE);
    private static final Pattern FORBIDDEN_TOKEN = Pattern.compile(
            "economy|vault|sql|jdbc|coreprotect|luckperms|secret|password|token",
            Pattern.CASE_INSENSITIVE);

    private static List<Path> sources() throws IOException {
        if (!Files.isDirectory(EVENT_SRC)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(EVENT_SRC)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static UUID world() {
        return UUID.randomUUID();
    }

    private static ChunkKey chunk(UUID worldId, int x, int z) {
        return new ChunkKey(worldId, x, z);
    }

    @Test
    void preEventsAreCancellableBeforeAnySideEffect() {
        UUID actor = UUID.randomUUID();
        UUID worldId = world();
        LandId land = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        Set<ChunkKey> chunks = Set.of(chunk(worldId, 0, 0));

        LandCreatePreEvent create = new LandCreatePreEvent(actor, worldId, owner, chunks, "Home");
        assertFalse(create.isCancelled());
        create.setCancelled(true);
        assertTrue(create.isCancelled());

        LandDeletePreEvent delete = new LandDeletePreEvent(actor, worldId, land);
        delete.setCancelled(true);
        assertTrue(delete.isCancelled());

        LandChunkAddPreEvent add = new LandChunkAddPreEvent(actor, worldId, land, chunks);
        add.setCancelled(true);
        assertTrue(add.isCancelled());

        LandChunkRemovePreEvent remove = new LandChunkRemovePreEvent(actor, worldId, land, chunks);
        remove.setCancelled(true);
        assertTrue(remove.isCancelled());

        SubLandPreEvent sub = new SubLandPreEvent(actor, land,
                new com.smile.chunkland.api.land.SubLandId(UUID.randomUUID()),
                SubLandPreEvent.Operation.CREATE);
        sub.setCancelled(true);
        assertTrue(sub.isCancelled());
    }

    @Test
    void postEventsCarryNoCancellableSurface() {
        for (Class<?> type : List.of(LandCreatePostEvent.class, LandDeletePostEvent.class,
                LandChunkAddPostEvent.class, LandChunkRemovePostEvent.class, SubLandPostEvent.class,
                LandEnterEvent.class, LandLeaveEvent.class, SubLandEnterEvent.class,
                SubLandLeaveEvent.class, PermissionChangedEvent.class, RuleChangedEvent.class)) {
            assertFalse(ChunkLandCancellable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must not be cancellable");
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertTrue(Modifier.isFinal(field.getModifiers()),
                        type.getSimpleName() + "." + field.getName() + " must be final");
            }
        }
    }

    @Test
    void preEventChunkSetsAreDefensivelyCopiedAndBounded() {
        UUID actor = UUID.randomUUID();
        UUID worldId = world();
        LandId land = new LandId(UUID.randomUUID());
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        Set<ChunkKey> mutable = new HashSet<>();
        mutable.add(chunk(worldId, 0, 0));

        LandCreatePreEvent create = new LandCreatePreEvent(actor, worldId, owner, mutable, "Home");
        mutable.add(chunk(worldId, 1, 1));
        assertEquals(1, create.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> create.chunks().add(chunk(worldId, 2, 2)));

        Set<ChunkKey> oversized = new HashSet<>();
        for (int i = 0; i < LandCreatePreEvent.MAX_CHUNKS + 1; i++) {
            oversized.add(chunk(worldId, i, 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new LandCreatePreEvent(actor, worldId, owner, oversized, "Home"));
        assertThrows(IllegalArgumentException.class,
                () -> new LandChunkAddPreEvent(actor, worldId, land, oversized));
        assertThrows(IllegalArgumentException.class,
                () -> new LandChunkRemovePreEvent(actor, worldId, land, oversized));
    }

    @Test
    void preEventsRejectBlankDisplayNameAndNulls() {
        UUID actor = UUID.randomUUID();
        UUID worldId = world();
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        Set<ChunkKey> chunks = Set.of(chunk(worldId, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LandCreatePreEvent(actor, worldId, owner, chunks, "  "));
        assertThrows(NullPointerException.class,
                () -> new LandCreatePreEvent(actor, worldId, owner, null, "Home"));
    }

    @Test
    void noopBusNeverCancelsAndNeverThrows() {
        ChunkLandEventBus bus = NoopChunkLandEventBus.instance();
        LandDeletePreEvent pre = new LandDeletePreEvent(UUID.randomUUID(), world(), new LandId(UUID.randomUUID()));
        bus.publish(pre);
        assertFalse(pre.isCancelled());
    }

    @Test
    void transitionAndChangedEventsRequireRealIdentities() {
        UUID player = UUID.randomUUID();
        UUID worldId = world();
        LandId land = new LandId(UUID.randomUUID());
        new LandEnterEvent(player, land, worldId);
        new LandLeaveEvent(player, land, worldId);
        new SubLandEnterEvent(player, land,
                new com.smile.chunkland.api.land.SubLandId(UUID.randomUUID()));
        new SubLandLeaveEvent(player, land,
                new com.smile.chunkland.api.land.SubLandId(UUID.randomUUID()));
        new PermissionChangedEvent(UUID.randomUUID(), land, null,
                PermissionChangedEvent.ChangeKind.TRUST,
                PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(), null, null, null);
        new RuleChangedEvent(UUID.randomUUID(), land, LandRuleType.PVP, null, PermissionState.DENY);
        assertThrows(NullPointerException.class, () -> new LandEnterEvent(null, land, worldId));
        assertThrows(NullPointerException.class,
                () -> new RuleChangedEvent(UUID.randomUUID(), land, null, null, PermissionState.DENY));
        assertThrows(NullPointerException.class,
                () -> new PermissionChangedEvent(null, land, null,
                        PermissionChangedEvent.ChangeKind.TRUST,
                        PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(),
                        null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PermissionChangedEvent(UUID.randomUUID(), null, null,
                        PermissionChangedEvent.ChangeKind.BIND,
                        PermissionChangedEvent.SubjectKind.PLAYER, UUID.randomUUID(),
                        ProtectionActionType.BLOCK_BREAK, PermissionState.ALLOW, null));
    }

    @Test
    void eventPackageStaysDependencyFree() throws IOException {
        List<Path> files = sources();
        assertFalse(files.isEmpty(), "event package must contain sources when run from the module dir");
        for (Path file : files) {
            String source = Files.readString(file);
            if (FORBIDDEN_IMPORT.matcher(source).find()) {
                fail("Forbidden dependency import in " + file.getFileName());
            }
            String body = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
            if (FORBIDDEN_TOKEN.matcher(body).find()) {
                fail("Forbidden external payload token in " + file.getFileName());
            }
        }
    }
}
