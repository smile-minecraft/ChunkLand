package com.smile.chunkland.rule;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.protection.PermissionDefaultsCache;
import com.smile.chunkland.runtime.api.LandRuleLookup;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Memory-only {@link LandRuleLookup} over one captured config view plus one
 * captured durable authorisation snapshot per read.
 *
 * <p>Each call reads the view source once and the durable source once, then
 * answers from that captured pair through {@link LandRuleViewService#one}:
 * the land override first, then the world default, then the global default,
 * then the built-in default. The given {@link LandRegistry} snapshot is the
 * same instance the caller used for the existence check, so that check and
 * the lookup cannot observe different land publications and the lookup never
 * re-reads a newer land publication. The view and the durable snapshot travel
 * through separate volatile references, so a reload landing between the two
 * reads can pair adjacent generations; each captured value on its own stays a
 * complete old or complete new version.
 *
 * <p>An unloaded durable snapshot, a missing or failing view source, an
 * unknown land, or any lookup failure answers {@link Optional#empty()} so
 * callers show unavailable instead of a wrong source. That empty matches the
 * enforcement side failing closed: unverifiable rules deny there instead of
 * falling through to a world or global {@code ALLOW}.
 *
 * <p>The query names the owning land's own policy. It carries no block
 * position, so it cannot pick which subland covers a position and does not
 * consult subland rules. Callers needing a position-aware decision use the
 * block decision path instead.
 *
 * <p>Hot-path rules: two volatile reads plus immutable map reads only. No
 * Bukkit, no SQL, no economy, no disk, no chunk load, no blocking wait, and
 * no cross-region player read. The lookup never sees an actor, so the owner
 * of the land is bound by the same effective rule as anyone else.
 */
public final class SnapshotLandRuleLookup implements LandRuleLookup {

    private final Supplier<PermissionDefaultsCache.ConfigView> views;
    private final Supplier<LandAuthorisationSnapshot> auths;

    /**
     * @param views live config view source; a {@code null} source or a source
     *              that returns {@code null} or throws reads as unavailable
     * @param auths durable authorisation source; a {@code null} source, a
     *              {@code null} answer, a throwing source, or an unloaded
     *              snapshot all read as unavailable
     */
    public SnapshotLandRuleLookup(Supplier<PermissionDefaultsCache.ConfigView> views,
            Supplier<LandAuthorisationSnapshot> auths) {
        this.views = views;
        this.auths = auths;
    }

    @Override
    public Optional<PermissionState> getRule(LandId landId, LandRuleType rule, LandRegistry snapshot) {
        Objects.requireNonNull(landId, "landId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(snapshot, "snapshot");
        LandSnapshot land;
        try {
            land = snapshot.land(landId);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
        if (land == null) {
            return Optional.empty();
        }
        PermissionDefaultsCache.ConfigView view;
        try {
            view = views == null ? null : views.get();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (view == null) {
            return Optional.empty();
        }
        LandAuthorisationSnapshot auth;
        try {
            auth = auths == null ? null : auths.get();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (auth == null || !auth.loaded()) {
            return Optional.empty();
        }
        try {
            return LandRuleViewService.one(landId, rule, snapshot, auth, view.snapshot())
                    .map(LandRuleView::effective)
                    .filter(state -> state != null && state != PermissionState.INHERIT);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }
}
