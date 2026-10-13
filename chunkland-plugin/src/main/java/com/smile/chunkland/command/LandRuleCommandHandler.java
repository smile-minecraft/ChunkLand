package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.persistence.LandRuleConflictException;
import com.smile.chunkland.persistence.StaleAuthorisationException;
import com.smile.chunkland.rule.LandRuleView;
import com.smile.chunkland.rule.RulePublishException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Production {@code /land rule} handler.
 *
 * <p>Three shapes: bare {@code /land rule} lists all eleven rules with
 * their explicit, effective and source values; {@code /land rule <rule>}
 * shows one; {@code /land rule <rule> <ALLOW|DENY|INHERIT>} persists the
 * land override ({@code INHERIT} deletes the row) through the conditional
 * mutation with the observed baseline pinned, so a stale view can never
 * silently overwrite a newer change. The domain gate
 * ({@code MANAGE_PERMISSION}) is enforced by the {@link LandCommand}
 * dispatcher before this handler runs.
 *
 * <p>Query replies use the existing label vars: the rule travels as
 * {@code action} (localized where a display name exists, otherwise raw),
 * the explicit value as {@code state}, the effective value as
 * {@code outcome}, and the source layer as {@code layer} (a short source
 * token). A committed but unpublished write answers
 * {@code command.land.rule.degraded} distinctly from
 * {@code command.land.rule.failed}, so the caller can tell "saved but not
 * live yet" apart from "never written".
 *
 * <p>Threading follows the default handler: sender-facing resolution runs
 * synchronously on the calling thread and only the reply runs on mutation
 * completion with captured values.
 */
public final class LandRuleCommandHandler implements LandCommand.Handler {

    /** Durable conditional mutation entry, kept as a seam so tests observe the call. */
    @FunctionalInterface
    public interface RuleMutation {
        CompletionStage<Void> apply(UUID actor, LandId landId, LandRuleType rule,
                PermissionState expectedCurrent, long expectedRevision, PermissionState state);
    }

    /** Resolves the affected land for the sender; empty means fail closed. */
    @FunctionalInterface
    public interface LandResolver {
        Optional<LandId> resolve(CommandSender sender);
    }

    /**
     * Write baseline observed before the mutation: the current explicit
     * value plus the authorisation generation pinned at gate time.
     */
    public record Baseline(PermissionState current, long revision) {
        public Baseline {
            Objects.requireNonNull(current, "current");
        }
    }

    /**
     * Reads the write baseline for one land and rule; empty means fail
     * closed without writing.
     */
    @FunctionalInterface
    public interface BaselineSource {
        Optional<Baseline> read(LandId landId, LandRuleType rule);
    }

    /**
     * Reads the display rows for one land; empty means the data is not
     * ready and the query answers unavailable without writing.
     */
    @FunctionalInterface
    public interface ViewSource {
        Optional<List<LandRuleView>> read(LandId landId);
    }

    private final LandResolver lands;
    private final RuleMutation mutation;
    private final BaselineSource baselines;
    private final ViewSource views;

    /**
     * @param lands affected-land resolver; null or empty resolves fail closed
     * @param mutation durable mutation; null replies unavailable without side effects
     * @param baselines baseline source; null or empty reads fail closed without writing
     * @param views display rows; null or empty reads answer unavailable without writing
     */
    public LandRuleCommandHandler(LandResolver lands, RuleMutation mutation,
            BaselineSource baselines, ViewSource views) {
        this.lands = lands;
        this.mutation = mutation;
        this.baselines = baselines;
        this.views = views;
    }

    /** Case-insensitive rule parse; unknown or blank names yield empty. */
    public static Optional<LandRuleType> parseRule(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LandRuleType.valueOf(
                    name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Rule names for tab completion, in declaration order. */
    public static List<String> ruleNames() {
        return java.util.Arrays.stream(LandRuleType.values()).map(Enum::name).toList();
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        if (!(sender instanceof Player player)) {
            sink.reply("command.land.rule.console", Map.of());
            return;
        }
        UUID actor;
        try {
            actor = player.getUniqueId();
        } catch (RuntimeException failure) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.failed"));
            return;
        }
        if (args == null || args.length < 1 || args.length > 3) {
            sink.reply("command.land.rule.usage", Map.of());
            return;
        }
        if (args.length == 1) {
            queryAll(sender, sink);
            return;
        }
        Optional<LandRuleType> rule = parseRule(args[1]);
        if (rule.isEmpty()) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.unknown_rule"));
            return;
        }
        if (args.length == 2) {
            queryOne(sender, sink, rule.get());
            return;
        }
        PermissionState state = parseState(args[2]);
        if (state == null) {
            sink.reply("command.land.rule.usage", Map.of());
            return;
        }
        write(actor, sender, sink, rule.get(), state);
    }

    private void queryAll(CommandSender sender, ReplySink sink) {
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.unknown_land"));
            return;
        }
        Optional<List<LandRuleView>> rows = readViews(land.get());
        if (rows.isEmpty() || rows.get() == null) {
            sink.reply("command.land.rule.unavailable", Map.of());
            return;
        }
        for (LandRuleView view : rows.get()) {
            if (view != null) {
                sink.reply("command.land.rule.line", lineVars(view));
            }
        }
    }

    private void queryOne(CommandSender sender, ReplySink sink, LandRuleType rule) {
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.unknown_land"));
            return;
        }
        Optional<List<LandRuleView>> rows = readViews(land.get());
        if (rows.isEmpty() || rows.get() == null) {
            sink.reply("command.land.rule.unavailable", Map.of());
            return;
        }
        for (LandRuleView view : rows.get()) {
            if (view != null && view.rule() == rule) {
                sink.reply("command.land.rule.line", lineVars(view));
                return;
            }
        }
        sink.reply("command.land.rule.unavailable", Map.of());
    }

    private void write(UUID actor, CommandSender sender, ReplySink sink, LandRuleType rule,
            PermissionState state) {
        if (mutation == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.unavailable"));
            return;
        }
        Optional<LandId> land = resolveLand(sender);
        if (land.isEmpty() || land.get() == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.unknown_land"));
            return;
        }
        LandId landId = land.get();
        Optional<Baseline> baseline = readBaseline(landId, rule);
        if (baseline.isEmpty() || baseline.get() == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.failed"));
            return;
        }
        Baseline pinned = baseline.get();
        CompletionStage<Void> stage;
        try {
            stage = mutation.apply(actor, landId, rule, pinned.current(),
                    pinned.revision(), state);
        } catch (RuntimeException failure) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.rule.failed", Map.of("reason", "rule.failed"));
            return;
        }
        stage.whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException
                            && failure.getCause() != null ? failure.getCause() : failure;
                    if (cause instanceof RulePublishException published) {
                        sink.reply("command.land.rule.degraded", Map.of(
                                "action", published.rule().name(),
                                "reason", "rule.publish_failed"));
                    } else if (cause instanceof LandRuleConflictException
                            || cause instanceof StaleAuthorisationException) {
                        sink.reply("command.land.rule.failed",
                                Map.of("reason", "rule.stale"));
                    } else {
                        sink.reply("command.land.rule.failed",
                                Map.of("reason", "rule.failed"));
                    }
                } else {
                    sink.reply("command.land.rule.success",
                            Map.of("action", rule.name(), "state", state.name()));
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

    private Optional<Baseline> readBaseline(LandId landId, LandRuleType rule) {
        if (baselines == null || landId == null || rule == null) {
            return Optional.empty();
        }
        try {
            Optional<Baseline> found = baselines.read(landId, rule);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private Optional<List<LandRuleView>> readViews(LandId landId) {
        if (views == null || landId == null) {
            return Optional.empty();
        }
        try {
            Optional<List<LandRuleView>> found = views.read(landId);
            return found == null ? Optional.empty() : found;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> lineVars(LandRuleView view) {
        return Map.of(
                "action", view.rule().name(),
                "state", view.explicit().name(),
                "outcome", view.effective().name(),
                "layer", view.source().name());
    }

    private static PermissionState parseState(String raw) {
        try {
            return PermissionState.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }
}
