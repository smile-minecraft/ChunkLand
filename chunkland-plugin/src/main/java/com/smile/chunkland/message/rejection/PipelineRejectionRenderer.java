package com.smile.chunkland.message.rejection;

import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.message.ChunkLandMessagePipeline;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Production {@link RejectionNotifier.Renderer}: renders the rejection
 * template matching the deny through the message pipeline.
 *
 * <p>Routing: an {@code ENTRY} deny whose explanation is the banned-inside
 * marker renders {@code protection.rejection.banned_inside}; any other
 * {@code ENTRY} deny renders {@code protection.rejection.entry_denied}, or
 * {@code entry_denied_at} when the land at the deny site is known; every
 * other action keeps the shared {@code action_denied} template with the
 * {@code action}/{@code reason} vars. The marker itself is consumed by the
 * branch and never sent as visible text.
 *
 * <p>Nothing technical reaches the player: the {@code action} var is the
 * action's localized display name (the pipeline swaps the constant for its
 * {@code permission.action.*} entry), and the {@code reason} var is one of
 * the {@code protection.rejection.reason.*} sentences chosen from the
 * decision trace, never the trace itself. The land name comes from the
 * injected {@link LandNames} seam, a memory-only snapshot read.
 *
 * <p>The locale follows the denied player (stored preference, then client
 * locale, then the pipeline default). Reading it only touches the player's
 * own in-memory state; if that read fails the notice falls back to the
 * pipeline default instead of being dropped. The actual send hops to the
 * player thread inside the {@link RejectionNotifier.Sender}. Any pipeline
 * failure surfaces as a {@link RuntimeException}, which the notifier already
 * swallows into a silent {@code false}.
 */
public final class PipelineRejectionRenderer implements RejectionNotifier.Renderer {

    /** Shared template; uses only the always-supplied {@code action}/{@code reason} vars. */
    public static final String MESSAGE_KEY = "protection.rejection.action_denied";

    /** ENTRY deny template; carries no vars. */
    public static final String ENTRY_DENIED_KEY = "protection.rejection.entry_denied";

    /** ENTRY deny template naming the land through {@code land_name}. */
    public static final String ENTRY_DENIED_AT_KEY = "protection.rejection.entry_denied_at";

    /** Banned-inside stop template; carries no vars. */
    public static final String BANNED_INSIDE_KEY = "protection.rejection.banned_inside";

    /** The player holds no grant on the named land. */
    public static final String REASON_NOT_MEMBER_KEY = "protection.rejection.reason.not_member";

    /** A grant on the named land denies the player outright. */
    public static final String REASON_EXPLICIT_KEY = "protection.rejection.reason.explicit";

    /** The covering subland of the named land denies by default. */
    public static final String REASON_SUBLAND_KEY = "protection.rejection.reason.subland";

    /** Land data could not be read, so the action was refused to stay safe. */
    public static final String REASON_UNAVAILABLE_KEY = "protection.rejection.reason.unavailable";

    /** Fallback when the land at the site is unknown. */
    public static final String REASON_NO_PERMISSION_KEY =
            "protection.rejection.reason.no_permission";

    /**
     * Memory-only land-name read for the deny site.
     *
     * @return the display name of the land owning the site, or {@code null}
     *         when no land owns it or the snapshot cannot answer
     */
    @FunctionalInterface
    public interface LandNames {
        String landNameAt(RejectionSite site);
    }

    private final ChunkLandMessagePipeline pipeline;
    private final LandNames lands;

    public PipelineRejectionRenderer(ChunkLandMessagePipeline pipeline) {
        this(pipeline, null);
    }

    /**
     * @param lands land-name seam; {@code null} keeps every notice generic
     *              (no land is ever named)
     */
    public PipelineRejectionRenderer(ChunkLandMessagePipeline pipeline, LandNames lands) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.lands = lands;
    }

    /**
     * Selects the template key for a deny without rendering.
     *
     * @param action the denied action, never {@code null}
     * @param explanation the decision explanation, may be {@code null}
     * @return the message key to render
     */
    public static String routeKey(ProtectionActionType action, String explanation) {
        Objects.requireNonNull(action, "action");
        if (action == ProtectionActionType.ENTRY) {
            if (RejectionNotifier.BANNED_INSIDE_REASON.equals(explanation)) {
                return BANNED_INSIDE_KEY;
            }
            return ENTRY_DENIED_KEY;
        }
        return MESSAGE_KEY;
    }

    /**
     * Selects the reason sentence for a deny from its decision trace. The
     * trace prefixes are the resolver's own layer labels; anything
     * unrecognized reads as the ordinary "not a member" case.
     *
     * @param explanation the decision explanation, may be {@code null}
     * @param landKnown whether the land at the site could be named
     * @return the reason message key to render
     */
    public static String reasonKey(String explanation, boolean landKnown) {
        String trace = explanation == null ? "" : explanation;
        if (trace.startsWith("Fail-closed")) {
            return REASON_UNAVAILABLE_KEY;
        }
        if (!landKnown) {
            return REASON_NO_PERMISSION_KEY;
        }
        if (trace.startsWith("SubLand binding") || trace.startsWith("Land binding")) {
            return REASON_EXPLICIT_KEY;
        }
        if (trace.startsWith("SubLand default")) {
            return REASON_SUBLAND_KEY;
        }
        return REASON_NOT_MEMBER_KEY;
    }

    @Override
    public Component render(Player player, ProtectionActionType action,
                            PermissionDecision decision) {
        return render(player, action, decision, null);
    }

    @Override
    public Component render(Player player, ProtectionActionType action,
                            PermissionDecision decision, RejectionSite site) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(decision, "decision");
        String landName = landNameAt(site);
        String key = routeKey(action, decision.explanation());
        if (BANNED_INSIDE_KEY.equals(key)) {
            return renderFor(player, key, Map.of());
        }
        if (ENTRY_DENIED_KEY.equals(key)) {
            if (landName == null) {
                return renderFor(player, key, Map.of());
            }
            return renderFor(player, ENTRY_DENIED_AT_KEY, Map.of("land_name", landName));
        }
        String reasonKey = reasonKey(decision.explanation(), landName != null);
        Map<String, Object> reasonVars = landName == null
                ? Map.of()
                : Map.of("land_name", landName);
        String reason = PlainTextComponentSerializer.plainText()
                .serialize(renderFor(player, reasonKey, reasonVars));
        return renderFor(player, MESSAGE_KEY,
                Map.of("action", action.name(), "reason", reason));
    }

    private String landNameAt(RejectionSite site) {
        LandNames lookup = this.lands;
        if (lookup == null || site == null) {
            return null;
        }
        try {
            String name = lookup.landNameAt(site);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /**
     * Renders in the denied player's locale; when the player-context render
     * itself fails, retries without the player so the pipeline default
     * locale still produces the notice.
     */
    private Component renderFor(Player player, String key, Map<String, Object> vars) {
        try {
            return pipeline.render(key, vars, null, player);
        } catch (RuntimeException failed) {
            return pipeline.render(key, vars, null, null);
        }
    }
}
