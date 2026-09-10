package com.smile.chunkland.command;

import com.smile.chunkland.message.ChunkLandMessagePipeline;
import com.smile.chunkland.protection.ManagementPermissionGate;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Deterministic dispatcher for {@code /land}.
 *
 * <p>Routing is case-insensitive, empty or unknown args route to help/unknown
 * messages via {@link ReplySink}. Every registered subcommand is gated by an
 * explicit permission node; deny replies through the message pipeline and
 * never invokes the handler. Handlers are injected so tests can observe
 * allow/deny without starting a server; production handlers are not-yet stubs
 * that must not call selection, mutation, economy or persistence.</p>
 *
 * <p>No revision validation, cooldown or Bedrock Form is performed here —
 * those live in the handlers: chat confirmations revalidate the token pair
 * in {@link ConfirmCommandHandler}, and the Bedrock Modal Form branch lives
 * in {@link BedrockClaimFormHandler} behind {@code /land claim}.
 * Management subcommands additionally pass
 * the shared domain gate: the resolver supplies the actor, target land,
 * snapshot, bypass and steward inputs, and this dispatcher always feeds them
 * through {@link ManagementPermissionGate#check} itself. There is no
 * allow-predicate seam — an unresolved, missing or throwing resolver fails
 * closed; full land-context resolution for that gate arrives with the
 * management flows.</p>
 */
public final class LandCommand {

    /** Handler for a single subcommand. */
    @FunctionalInterface
    public interface Handler {
        void handle(CommandSender sender, String[] args, ReplySink sink);
    }

    public static final List<String> SUBCOMMANDS = List.of(
            "help", "confirm", "wand", "claim", "trust", "untrust", "default", "ban", "unban", "subland", "expand", "shrink", "unclaim", "rename", "delete", "group");

    private final Map<String, Handler> handlers;
    private final BiFunction<CommandSender, ChunkLandMessagePipeline, ReplySink> sinkFactory;
    private final ManagementGateResolver gateResolver;

    public LandCommand(Map<String, Handler> handlers,
                       BiFunction<CommandSender, ChunkLandMessagePipeline, ReplySink> sinkFactory) {
        this(handlers, sinkFactory, null);
    }

    /**
     * Dispatcher with a domain gate resolver for management subcommands.
     *
     * <p>The resolver adapts Bukkit state into the shared
     * {@link ManagementPermissionGate} inputs; the dispatcher itself runs the
     * gate check, so a resolver can never grant access on its own. A denial
     * refuses the operation even when the Bukkit command node passed, and the
     * handler is never invoked. A {@code null}, empty or throwing resolver
     * fails closed on management subcommands (deny without invoking the
     * handler); only subcommands without a management action skip the gate.
     */
    public LandCommand(Map<String, Handler> handlers,
                       BiFunction<CommandSender, ChunkLandMessagePipeline, ReplySink> sinkFactory,
                       ManagementGateResolver gateResolver) {
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
        this.sinkFactory = sinkFactory == null ? PipelineReplySink::new : sinkFactory;
        this.gateResolver = gateResolver;
    }

    /**
     * Maps a subcommand to the management action enforced by the shared domain
     * gate. Subcommands without a management action yield
     * {@link Optional#empty()} and are never sent to the authorizer.
     */
    public static Optional<ProtectionActionType> managementActionFor(String subcommand) {
        return ManagementPermissionGate.actionForSubcommand(subcommand);
    }

    /** Production not-yet handler map. */
    public static Map<String, Handler> defaultStubHandlers() {
        Map<String, Handler> m = new HashMap<>();
        for (String sub : SUBCOMMANDS) {
            final String captured = sub;
            if (captured.equals("help")) {
                m.put(captured, (sender, args, sink) -> sink.reply("command.land.help", Map.of()));
            } else if (captured.equals("confirm")) {
                m.put(captured, (sender, args, sink) -> {
                    // confirm may carry a revision token but stub does not validate it.
                    sink.reply("command.land.not_yet", Map.of("subcommand", captured));
                });
            } else if (captured.equals("trust")) {
                // Direct trust is implemented; without a wired mutation the
                // slot stays fail-closed instead of pretending it is coming soon.
                m.put(captured, (sender, args, sink) ->
                        sink.reply("command.land.trust.failed", Map.of("reason", "trust.unavailable")));
            } else if (captured.equals("untrust")) {
                m.put(captured, (sender, args, sink) ->
                        sink.reply("command.land.untrust.failed", Map.of("reason", "untrust.unavailable")));
            } else if (captured.equals("default")) {
                m.put(captured, (sender, args, sink) ->
                        sink.reply("command.land.default.failed", Map.of("reason", "default.unavailable")));
            } else {
                m.put(captured, (sender, args, sink) -> sink.reply("command.land.not_yet", Map.of("subcommand", captured)));
            }
        }
        return Collections.unmodifiableMap(m);
    }

    // -----------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------

    /**
     * Bukkit entry-point style dispatch.
     *
     * @param sender   command sender
     * @param command  Bukkit command (name must be {@code land})
     * @param label    alias used
     * @param args     subcommand + tail
     * @param pipeline nullable pipeline (when null or rendering fails, sink is fail-closed with no output)
     * @return true when the command was recognised as {@code /land}
     */
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args, ChunkLandMessagePipeline pipeline) {
        if (command != null && !command.getName().equalsIgnoreCase("land")) {
            return false;
        }
        return dispatch(sender, args, pipeline);
    }

    /**
     * Pure dispatch for tests and the plugin entry-point.
     *
     * @return true when {@code /land} was recognised (Bukkit should not try aliases)
     */
    public boolean dispatch(CommandSender sender, String[] args, ChunkLandMessagePipeline pipeline) {
        Objects.requireNonNull(sender, "sender");
        ReplySink sink = sinkFactory.apply(sender, pipeline);
        if (args == null || args.length == 0) {
            String perm = LandPermissions.forSubcommand("help");
            if (perm != null && !sender.hasPermission(perm)) {
                sink.reply("command.land.denied", Map.of("permission", perm));
                return true;
            }
            sink.reply("command.land.help", Map.of());
            return true;
        }
        String raw = args[0];
        if (raw == null || raw.isBlank()) {
            String perm = LandPermissions.forSubcommand("help");
            if (perm != null && !sender.hasPermission(perm)) {
                sink.reply("command.land.denied", Map.of("permission", perm));
                return true;
            }
            sink.reply("command.land.help", Map.of());
            return true;
        }
        String sub = raw.toLowerCase(Locale.ROOT);
        if (sub.equals("help") || sub.equals("?")) {
            String perm = LandPermissions.forSubcommand("help");
            if (perm != null && !sender.hasPermission(perm)) {
                sink.reply("command.land.denied", Map.of("permission", perm));
                return true;
            }
            Handler h = handlers.get("help");
            if (h != null) {
                h.handle(sender, args, sink);
            } else {
                sink.reply("command.land.help", Map.of());
            }
            return true;
        }
        if (!SUBCOMMANDS.contains(sub)) {
            sink.reply("command.land.unknown", Map.of("subcommand", raw));
            return true;
        }
        String perm = LandPermissions.forSubcommand(sub);
        if (perm != null && !sender.hasPermission(perm)) {
            sink.reply("command.land.denied", Map.of("permission", perm));
            return true;
        }
        Optional<ProtectionActionType> managed = managementActionFor(sub);
        if (managed.isPresent()) {
            if (!checkManagementGate(sender, managed.get(), args)) {
                sink.reply("command.land.denied", Map.of("permission", managed.get().name()));
                return true;
            }
        }
        Handler h = handlers.get(sub);
        if (h != null) {
            h.handle(sender, args, sink);
        } else {
            sink.reply("command.land.not_yet", Map.of("subcommand", sub));
        }
        return true;
    }

    /**
     * Runs the shared domain gate for one management subcommand. The resolver
     * only supplies inputs; the ALLOW/DENY verdict always comes from
     * {@link ManagementPermissionGate#check} here. Any missing, empty or
     * failing resolution denies without invoking the handler.
     */
    private boolean checkManagementGate(CommandSender sender, ProtectionActionType action, String[] args) {
        if (gateResolver == null) {
            return false;
        }
        Optional<ManagementGateResolver.Request> resolved;
        try {
            resolved = gateResolver.resolve(sender, action, args);
        } catch (RuntimeException unresolved) {
            return false;
        }
        if (resolved == null || resolved.isEmpty() || resolved.get() == null) {
            return false;
        }
        ManagementGateResolver.Request request = resolved.get();
        try {
            return ManagementPermissionGate.check(
                    request.actor(),
                    request.landId(),
                    action,
                    request.snapshot(),
                    request.adminBypass(),
                    request.serverLandSteward(),
                    request.provider()).outcome() == PermissionState.ALLOW;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /**
     * Server-independent seam that lets tests observe handler invocation via a gate.
     */
    public static boolean dispatchForTest(CommandSender sender,
                                         String[] args,
                                         ChunkLandMessagePipeline pipeline,
                                         Map<String, Handler> handlers,
                                         BiFunction<CommandSender, ChunkLandMessagePipeline, ReplySink> sinkFactory) {
        LandCommand cmd = new LandCommand(handlers, sinkFactory);
        return cmd.dispatch(sender, args, pipeline);
    }

    // -----------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return tabComplete(sender, args);
    }

    public static List<String> tabComplete(CommandSender sender, String[] args) {
        if (args == null || args.length == 0) {
            return new ArrayList<>(SUBCOMMANDS);
        }
        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        // Subcommand-specific completions are not yet needed; later milestones may add revision etc.
        // Keep deterministic: no suggestions for tail args in skeleton.
        return List.of();
    }

    /** Accessor for wiring inspection. */
    public Map<String, Handler> handlers() {
        return handlers;
    }
}
