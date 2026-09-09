package com.smile.chunkland.command;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.chunkland.selection.SelectionPoint;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.selection.SelectionSessionManager;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionUpdate;
import com.smile.chunkland.selection.SelectionVisualizationTaskController;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Temporary {@code /chunkland viz} debug command for selection particle verification.
 *
 * <p>Verbs: {@code start} builds a 2x2-chunk demo session around the operating player's
 * current chunk and lets the session manager drive rendering; {@code stop} cancels it;
 * {@code status} reports whether a session and a render loop are live. Session creation
 * runs inside the player's own context ({@code runForPlayer}) so the location read and
 * all downstream particle ticks stay on the correct Folia thread even across regions.
 *
 * <p>Fail-closed everywhere: console senders, missing permission, missing selections, a
 * missing scheduler, or a retired player context all reply without touching selection
 * state. Chunk coordinates come from pure arithmetic on the player's own location —
 * no Highest Block lookup, no block data, no entity scan, no world/chunk query.
 */
public final class VisualizationDebugCommand {
    /** Permission key for {@code /chunkland viz}. */
    public static final String PERMISSION = "chunkland.debug.visualization";

    private VisualizationDebugCommand() {
        // static dispatcher only
    }

    /**
     * Handle {@code /chunkland viz ...}.
     *
     * @param sender the command sender (must be a player for every verb)
     * @param args full command args with {@code args[0]} equal to {@code "viz"}
     * @param selections live selection registry; null means the plugin never finished wiring
     * @param schedulers player-scoped scheduler source; empty means visualization is dormant
     * @param visualization render controller, for {@code status} reporting
     * @return always {@code true} once the entry-point is recognised
     */
    public static boolean handle(
            CommandSender sender,
            String[] args,
            SelectionSessionManager selections,
            Supplier<Optional<SafeScheduler>> schedulers,
            SelectionVisualizationTaskController visualization) {
        Objects.requireNonNull(sender, "sender");
        if (args == null || args.length < 1 || !"viz".equalsIgnoreCase(args[0])) {
            return false;
        }
        String verb = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        if (!(sender instanceof Player player)) {
            sender.sendMessage("ChunkLand viz 須由玩家執行（邊界渲染綁定到操作玩家）。");
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("ChunkLand：缺少權限 " + PERMISSION + "，拒絕執行 viz。");
            return true;
        }
        if (selections == null) {
            sender.sendMessage("ChunkLand viz 尚未就緒（selection registry 缺失）。");
            return true;
        }
        switch (verb) {
            case "start" -> start(player, selections, schedulers);
            case "stop" -> {
                boolean stopped = selections.cancel(player.getUniqueId());
                sender.sendMessage(stopped ? "ChunkLand viz 已停止。" : "ChunkLand viz 目前沒有作用中的選取。");
            }
            case "status" -> {
                boolean session = selections.sessionFor(player.getUniqueId()).isPresent();
                boolean rendering = visualization != null && visualization.isActive(player.getUniqueId());
                sender.sendMessage("ChunkLand viz 狀態：session=" + session + " rendering=" + rendering + "。");
            }
            default -> sender.sendMessage("ChunkLand viz 用法：/chunkland viz <start|stop|status>。");
        }
        return true;
    }

    private static void start(
            Player player,
            SelectionSessionManager selections,
            Supplier<Optional<SafeScheduler>> schedulers) {
        SafeScheduler scheduler = schedulers == null ? null : schedulers.get().orElse(null);
        if (scheduler == null) {
            player.sendMessage("ChunkLand viz 尚未就緒（visualization scheduler 缺失）。");
            return;
        }
        UUID playerId = player.getUniqueId();
        player.sendMessage("ChunkLand viz 啟動中（已排入玩家 context）…");
        try {
            scheduler.runForPlayer(player, () -> buildAndStart(player, playerId, selections));
        } catch (RuntimeException dispatchFailure) {
            player.sendMessage("ChunkLand viz 啟動失敗（scheduler 拒絕排程）。");
        }
    }

    private static void buildAndStart(Player player, UUID playerId, SelectionSessionManager selections) {
        Location location;
        try {
            location = player.getLocation();
        } catch (RuntimeException locationFailure) {
            player.sendMessage("ChunkLand viz 啟動失敗（無法讀取玩家位置）。");
            return;
        }
        if (location == null || location.getWorld() == null) {
            player.sendMessage("ChunkLand viz 啟動失敗（玩家尚未綁定世界）。");
            return;
        }
        UUID worldId = location.getWorld().getUID();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        int y = location.getBlockY();
        Set<com.smile.chunkland.api.land.ChunkKey> chunks = new LinkedHashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                chunks.add(new com.smile.chunkland.api.land.ChunkKey(worldId, chunkX + dx, chunkZ + dz));
            }
        }
        SelectionPoint pointA = new SelectionPoint(worldId, chunkX * 16, y, chunkZ * 16);
        SelectionPoint pointB = new SelectionPoint(worldId, chunkX * 16 + 31, y, chunkZ * 16 + 31);
        Instant now = Instant.now();
        try {
            SelectionSession stamped = selections.createOrReplace(SelectionSession.initial(
                    playerId,
                    worldId,
                    SelectionMode.CREATE_LAND,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(pointA),
                    Optional.of(pointB),
                    0,
                    now));
            Optional<SelectionSession> updated = selections.updateSelection(
                    playerId,
                    stamped,
                    new SelectionUpdate(Optional.of(pointA), Optional.of(pointB), Set.copyOf(chunks), Map.of()));
            if (updated.isPresent()) {
                player.sendMessage("ChunkLand viz 已啟動：2x2 chunks，revision="
                        + updated.get().selectionRevision() + "。");
            } else {
                player.sendMessage("ChunkLand viz 工作階段已建立，但選取更新未被接受。");
            }
        } catch (RuntimeException startFailure) {
            player.sendMessage("ChunkLand viz 啟動失敗（selection 操作被拒絕）。");
        }
    }
}
