package com.smile.chunkland.rule;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsSnapshot;
import com.smile.chunkland.runtime.index.LandRegistry;
import com.smile.chunkland.runtime.rule.LandRuleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only triple behind the rule query and the rule management pages.
 *
 * <p>Resolution order inside one call: Land rule → World default → Global
 * default → built-in default, mirroring {@link LandRuleService}. An
 * {@code INHERIT} (or missing) entry falls through to the next layer; the
 * built-in default is always concrete, so a known land with a loaded
 * snapshot always resolves to a concrete effective value.
 *
 * <p>The durable snapshot must be {@link LandAuthorisationSnapshot#loaded
 * loaded}: an unloaded snapshot answers {@code INHERIT} from
 * {@link LandAuthorisationSnapshot#landRule landRule} exactly like a land
 * without overrides, so callers check loaded-ness first and answer
 * unavailable instead of showing a wrong source. Unknown lands and
 * {@code null} inputs answer unavailable the same way, matching the
 * conservative rule layer (unverifiable rules deny fail-closed, never fall
 * through to a world or global {@code ALLOW}).
 */
public final class LandRuleViewService {

    private LandRuleViewService() {
    }

    /**
     * All eleven rules for one land, or empty when nothing may be shown
     * (unloaded snapshot, unknown land, missing input).
     */
    public static Optional<List<LandRuleView>> all(LandId landId, LandRegistry registry,
            LandAuthorisationSnapshot auth, PermissionDefaultsSnapshot defaults) {
        Resolved resolved = resolveInputs(landId, registry, auth, defaults);
        if (resolved == null) {
            return Optional.empty();
        }
        List<LandRuleView> views = new ArrayList<>(LandRuleType.values().length);
        for (LandRuleType rule : LandRuleType.values()) {
            views.add(view(resolved, rule));
        }
        return Optional.of(List.copyOf(views));
    }

    /** One rule for one land, or empty when nothing may be shown. */
    public static Optional<LandRuleView> one(LandId landId, LandRuleType rule,
            LandRegistry registry, LandAuthorisationSnapshot auth,
            PermissionDefaultsSnapshot defaults) {
        if (rule == null) {
            return Optional.empty();
        }
        Resolved resolved = resolveInputs(landId, registry, auth, defaults);
        if (resolved == null) {
            return Optional.empty();
        }
        return Optional.of(view(resolved, rule));
    }

    private record Resolved(UUID worldId, LandAuthorisationSnapshot auth,
            PermissionDefaultsSnapshot defaults, LandId landId,
            Map<LandRuleType, PermissionState> builtIn) {
    }

    private static Resolved resolveInputs(LandId landId, LandRegistry registry,
            LandAuthorisationSnapshot auth, PermissionDefaultsSnapshot defaults) {
        if (landId == null || registry == null || auth == null || defaults == null) {
            return null;
        }
        if (!auth.loaded()) {
            return null;
        }
        LandSnapshot land;
        try {
            land = registry.land(landId);
        } catch (RuntimeException unresolved) {
            return null;
        }
        if (land == null || land.worldId() == null) {
            return null;
        }
        return new Resolved(land.worldId(), auth, defaults, landId,
                LandRuleService.builtInDefaults());
    }

    private static LandRuleView view(Resolved resolved, LandRuleType rule) {
        PermissionState explicit = explicitOf(resolved, rule);
        if (explicit != PermissionState.INHERIT) {
            return new LandRuleView(rule, explicit, explicit, LandRuleView.Source.LAND);
        }
        PermissionState world = resolved.defaults().ruleWorldDefault(resolved.worldId(), rule);
        if (world != PermissionState.INHERIT) {
            return new LandRuleView(rule, PermissionState.INHERIT, world,
                    LandRuleView.Source.WORLD);
        }
        PermissionState global = resolved.defaults().ruleGlobalDefault(rule);
        if (global != PermissionState.INHERIT) {
            return new LandRuleView(rule, PermissionState.INHERIT, global,
                    LandRuleView.Source.GLOBAL);
        }
        PermissionState builtIn = resolved.builtIn().getOrDefault(rule, PermissionState.DENY);
        if (builtIn == PermissionState.INHERIT) {
            builtIn = PermissionState.DENY;
        }
        return new LandRuleView(rule, PermissionState.INHERIT, builtIn,
                LandRuleView.Source.BUILT_IN);
    }

    private static PermissionState explicitOf(Resolved resolved, LandRuleType rule) {
        try {
            PermissionState found = resolved.auth().landRule(resolved.landId(), rule);
            return found == null ? PermissionState.INHERIT : found;
        } catch (RuntimeException unresolved) {
            return PermissionState.INHERIT;
        }
    }
}
