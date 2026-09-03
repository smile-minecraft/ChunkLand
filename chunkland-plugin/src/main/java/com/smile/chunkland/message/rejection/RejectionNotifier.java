package com.smile.chunkland.message.rejection;

import com.smile.chunkland.api.permission.DecisionSource;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Throttled rejection notices for denied players.
 *
 * <p>Guard order is strict, and each early return costs nothing further down:
 * decision ({@code ALLOW} returns immediately) then {@code null} guards, then
 * the silent table, then the absent-pipeline check, then the
 * {@code (player, action)} cooldown. Only a deny that survives all of them
 * builds a {@link Component} via the {@link Renderer} and hands it to the
 * {@link Sender}.
 *
 * <p>Every failure is fail-closed and silent: an unknown pipeline
 * ({@code null} sender), a throwing renderer, or a throwing sender all yield
 * {@code false} without propagating. The caller's cancel decision is already
 * made before this runs, so messaging can never flip enforcement.
 */
public final class RejectionNotifier {

    /** Sends an already-built notice; production adapters wrap the pipeline. */
    public interface Sender {
        void send(Player player, Component message);
    }

    /** Builds the notice Component; invoked only for a real send. */
    public interface Renderer {
        Component render(Player player, ProtectionActionType action,
                         PermissionDecision decision);
    }

    private final Sender sender;
    private final Renderer renderer;
    private final RejectionCooldown cooldown;
    private final Set<ProtectionActionType> extraSilent;

    public RejectionNotifier(Sender sender, Renderer renderer, RejectionCooldown cooldown) {
        this(sender, renderer, cooldown, Set.of());
    }

    public RejectionNotifier(Sender sender, Renderer renderer, RejectionCooldown cooldown,
                              Set<ProtectionActionType> extraSilent) {
        this.sender = sender;
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.extraSilent = extraSilent == null ? Set.of() : Set.copyOf(extraSilent);
    }

    /**
     * Default silence rule: world-mechanic actions decide from land rules, not
     * from the player's rights, so denying them is routine enforcement rather
     * than a message the denied player can act on.
     */
    public static boolean isSilentByDefault(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        return action.decisionSource() == DecisionSource.LAND_RULE;
    }

    /** Default rule plus any extra silent actions configured for this notifier. */
    public boolean isSilent(ProtectionActionType action) {
        Objects.requireNonNull(action, "action");
        return extraSilent.contains(action) || isSilentByDefault(action);
    }

    /**
     * Notifies the denied player, unless an earlier guard suppresses it.
     *
     * @return {@code true} when a notice was actually sent; {@code false} for
     *         every suppressed or failed path (never throws for messaging
     *         failures)
     */
    public boolean notifyDenied(Player player, ProtectionActionType action,
                                 PermissionDecision decision) {
        if (decision == null || decision.outcome() != PermissionState.DENY) {
            return false;
        }
        if (player == null || action == null) {
            return false;
        }
        if (isSilent(action)) {
            return false;
        }
        if (sender == null) {
            return false;
        }
        try {
            UUID playerId = player.getUniqueId();
            if (playerId == null || !cooldown.tryAcquire(playerId, action)) {
                return false;
            }
        } catch (RuntimeException ex) {
            return false;
        }
        Component message;
        try {
            message = renderer.render(player, action, decision);
        } catch (RuntimeException ex) {
            return false;
        }
        if (message == null) {
            return false;
        }
        try {
            sender.send(player, message);
        } catch (RuntimeException ex) {
            return false;
        }
        return true;
    }
}
