package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.DirectTrustWhitelist;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land default <action> <ALLOW|DENY|INHERIT>} handler.
 *
 * <p>Persists the land default for one whitelisted subject action on the
 * land under the sender's current position: {@code ALLOW} and {@code DENY}
 * upsert the row, {@code INHERIT} deletes it. Management actions, land
 * rules and unknown names fail closed before any write, and the domain gate
 * ({@code MANAGE_PERMISSION}) is enforced by the {@link LandCommand}
 * dispatcher before this handler runs.
 *
 * <p>Threading follows the trust handler: sender-facing resolution runs
 * synchronously on the calling thread and only the reply runs on mutation
 * completion with captured values.
 */
public final class LandDefaultCommandHandler implements LandCommand.Handler {

    /** Durable mutation entry; kept as a seam so tests observe the call. */
    @FunctionalInterface
    public interface DefaultMutation {
        CompletionStage<Void> apply(UUID actor, LandId landId,
                ProtectionActionType action, PermissionState state);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    private final LandResolver lands;
    private final DefaultMutation mutation;

    /**
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param mutation durable mutation; null replies unavailable without side effects
     */
    public LandDefaultCommandHandler(LandResolver lands, DefaultMutation mutation) {
        this.lands = lands;
        this.mutation = mutation;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.default.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.failed"));
            return;
        }
        if (args == null || args.length < 3 || args[1] == null || args[2] == null
                || args[1].isBlank() || args[2].isBlank()) {
            sink.reply("command.land.default.usage", Map.of());
            return;
        }
        if (mutation == null) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.unavailable"));
            return;
        }
        Optional<ProtectionActionType> action = DirectTrustWhitelist.parseAllowed(args[1]);
        if (action.isEmpty()) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.unknown_action"));
            return;
        }
        PermissionState state = parseState(args[2]);
        if (state == null) {
            sink.reply("command.land.default.usage", Map.of());
            return;
        }
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.unknown_land"));
            return;
        }
        ProtectionActionType actionType = action.get();
        LandId landId = land.get();
        CompletionStage<Void> stage;
        try {
            stage = mutation.apply(actor, landId, actionType, state);
        } catch (RuntimeException failure) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.failed"));
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    sink.reply("command.land.default.failed", Map.of("reason", "default.failed"));
                } else {
                    sink.reply("command.land.default.success",
                            Map.of("action", actionType.name(), "state", state.name()));
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private Optional<LandId> resolveLand(CommandSender sender) {
        if (lands == null) {
            return Optional.empty();
        }
        try {
            Optional<LandId> found = lands.resolve(sender);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static PermissionState parseState(String raw) {
        try {
            return PermissionState.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }
}
