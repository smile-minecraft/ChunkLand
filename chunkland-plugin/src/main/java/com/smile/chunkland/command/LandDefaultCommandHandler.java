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

    /**
     * Durable mutation entry: the atomic compare-and-set behind both the
     * command and the GUI confirm path, kept as a seam so tests observe
     * the call. Both callers pin the generation they observed, so the
     * commit — not a pre-call snapshot — is the authority.
     */
    @FunctionalInterface
    public interface DefaultMutation {
        CompletionStage<Void> apply(UUID actor, LandId landId,
                ProtectionActionType action, PermissionState expectedCurrent,
                long expectedRevision, PermissionState state);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    /**
     * Write baseline observed before the mutation: the current default
     * value plus the authorisation generation pinned at gate time.
     */
    public record Baseline(PermissionState current, long revision) {
        public Baseline {
            Objects.requireNonNull(current, "current");
        }
    }

    /**
     * Reads the write baseline for one land and action; empty means fail
     * closed without writing.
     */
    @FunctionalInterface
    public interface BaselineSource {
        Optional<Baseline> read(LandId landId, ProtectionActionType action);
    }

    private final LandResolver lands;
    private final DefaultMutation mutation;
    private final BaselineSource baselines;

    /**
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param mutation durable mutation; null replies unavailable without side effects
     * @param baselines baseline source; null or empty reads fail closed without writing
     */
    public LandDefaultCommandHandler(LandResolver lands, DefaultMutation mutation,
            BaselineSource baselines) {
        this.lands = lands;
        this.mutation = mutation;
        this.baselines = baselines;
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
        Optional<Baseline> baseline = readBaseline(landId, actionType);
        if (baseline.isEmpty() || baseline.get() == null) {
            sink.reply("command.land.default.failed", Map.of("reason", "default.failed"));
            return;
        }
        Baseline pinned = baseline.get();
        CompletionStage<Void> stage;
        try {
            stage = mutation.apply(actor, landId, actionType, pinned.current(),
                    pinned.revision(), state);
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

    private Optional<Baseline> readBaseline(LandId landId, ProtectionActionType action) {
        if (baselines == null || landId == null || action == null) {
            return Optional.empty();
        }
        try {
            Optional<Baseline> found = baselines.read(landId, action);
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
