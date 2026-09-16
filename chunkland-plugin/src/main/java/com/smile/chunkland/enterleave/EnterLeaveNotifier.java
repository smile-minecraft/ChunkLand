package com.smile.chunkland.enterleave;

import com.smile.chunkland.command.PlayerScheduler;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Renders prompt notices as ActionBar messages.
 *
 * <p>Thread boundary: the caller runs on the event thread and only reads the
 * memory-only preference snapshot here; every {@link Player} touch (locale
 * read inside the pipeline, the send itself) runs on the player's thread
 * through the injected {@link PlayerScheduler}. A throwing scheduler (retired
 * entity scheduler, departed player) drops that notice fail-closed — never
 * retried, never leaked. A disabled switch suppresses the whole batch before
 * any scheduling.
 *
 * <p>Message keys (both locales carry the same placeholders):
 * <ul>
 *   <li>{@code land.enter.message} with {@code land_name}.</li>
 *   <li>{@code land.leave.message} with {@code land_name}.</li>
 *   <li>{@code land.subland.enter} with {@code land_name} and
 *   {@code sub_name}.</li>
 *   <li>{@code land.subland.leave} with {@code land_name} and
 *   {@code sub_name}.</li>
 * </ul>
 */
public final class EnterLeaveNotifier {

    public static final String ENTER_LAND_KEY = "land.enter.message";
    public static final String LEAVE_LAND_KEY = "land.leave.message";
    public static final String ENTER_SUB_KEY = "land.subland.enter";
    public static final String LEAVE_SUB_KEY = "land.subland.leave";

    private final ChunkLandMessagePipeline pipeline;
    private final EnterLeavePreferenceService preferences;
    private final PlayerScheduler scheduler;

    /**
     * @param pipeline message pipeline; {@code null} keeps the notifier
     *         silent (no message cost) for wiring without messaging
     */
    public EnterLeaveNotifier(ChunkLandMessagePipeline pipeline,
            EnterLeavePreferenceService preferences, PlayerScheduler scheduler) {
        this.pipeline = pipeline;
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Sends one ActionBar per notice, in order (a cross-land move renders
     * leave before enter). Returns the number of scheduled sends. Disabled
     * players, an empty batch or a missing pipeline schedule nothing.
     */
    public int notify(Player player, List<EnterLeaveNotice> notices) {
        Objects.requireNonNull(player, "player");
        if (notices == null || notices.isEmpty() || pipeline == null) {
            return 0;
        }
        UUID playerId;
        try {
            playerId = player.getUniqueId();
        } catch (RuntimeException ignored) {
            return 0;
        }
        if (playerId == null || !preferences.enabled(playerId)) {
            return 0;
        }
        int scheduled = 0;
        for (EnterLeaveNotice notice : notices) {
            if (notice == null) {
                continue;
            }
            if (scheduleOne(player, notice)) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private boolean scheduleOne(Player player, EnterLeaveNotice notice) {
        String key = keyOf(notice.kind());
        Map<String, Object> vars = varsOf(notice);
        if (key == null || vars == null) {
            return false;
        }
        try {
            scheduler.runForPlayer(player, () -> {
                try {
                    pipeline.sendActionBar(player, key, vars, null);
                } catch (RuntimeException ignored) {
                    // Render/send failures stay silent on the movement path.
                }
            });
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static String keyOf(NoticeKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case ENTER_LAND -> ENTER_LAND_KEY;
            case LEAVE_LAND -> LEAVE_LAND_KEY;
            case ENTER_SUB -> ENTER_SUB_KEY;
            case LEAVE_SUB -> LEAVE_SUB_KEY;
        };
    }

    static Map<String, Object> varsOf(EnterLeaveNotice notice) {
        EnterLeavePosition position = notice.position();
        String landName = position.landName();
        if (landName == null || landName.isBlank()) {
            return null;
        }
        return switch (notice.kind()) {
            case ENTER_LAND, LEAVE_LAND -> Map.of("land_name", landName);
            case ENTER_SUB, LEAVE_SUB -> {
                String subName = position.subName();
                if (subName == null || subName.isBlank()) {
                    yield null;
                }
                Map<String, Object> vars = new HashMap<>(2);
                vars.put("land_name", landName);
                vars.put("sub_name", subName);
                yield Map.copyOf(vars);
            }
        };
    }
}
