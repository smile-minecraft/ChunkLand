package com.smile.chunkland.runtime.mutation;

import com.smile.chunkland.api.event.PermissionChangedEvent;
import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.SubLandId;
import com.smile.chunkland.api.land.SubLandSnapshot;
import com.smile.chunkland.api.mutation.TypedMutationRequest;
import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.rule.LandRuleType;
import com.smile.chunkland.binding.LandBindingService;
import com.smile.chunkland.persistence.LandBindingRepository;
import com.smile.chunkland.protection.LandAuthorisationSnapshot;
import com.smile.chunkland.rule.LandRuleMutationService;
import com.smile.chunkland.selection.SelectionMode;
import com.smile.chunkland.selection.SelectionSession;
import com.smile.chunkland.subland.SubLandConfirmService;
import com.smile.chunkland.subland.SubLandMutationRunner;
import com.smile.chunkland.trust.LandAuthorisationService;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Production commit seams behind the public typed mutations for
 * sublands, permissions and rules.
 *
 * <p>Every seam delegates to the same service the chat and management
 * paths use — the SubLand mutation runner, the direct-trust and binding
 * services, and the conditional rule write — so the public entry never
 * owns a second write path, a second validator, or a second event.
 * The seams only adapt the typed request shape to the service entry;
 * authorisation (owner match plus the shared management gate) and
 * idempotency (the public operation ledger row) stay in the typed
 * facade, which calls these seams after its pre-checks.
 *
 * <p>Subland commits run through the production
 * {@link SubLandMutationRunner} with a synthetic confirmation triple
 * built from the request itself: the parent id, the expected structure
 * revision, and a session carrying no chat points (the precise cuboid
 * travels on the candidate, exactly as the chat handler builds it from
 * its own session). The runner's single-use confirmation mark is never
 * consumed — the synthetic session was never accepted through the
 * confirm service, so its release calls are no-ops — and idempotency
 * comes from the ledger row instead. Audit rows therefore carry zero
 * session numbers: no chat session exists on this path.
 */
public final class ProductionMutationPorts {

    private ProductionMutationPorts() {
    }

    /**
     * Subland commits through the production runner.
     *
     * @param runner production SubLand mutation runner; never {@code null}
     * @param clock instant source for the synthetic session; never {@code null}
     */
    public static SublandWritePort sublands(SubLandMutationRunner runner, Clock clock) {
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(clock, "clock");
        return new SublandWritePort() {
            @Override
            public CompletionStage<SubLandSnapshot> create(
                    UUID actor, LandSnapshot parent, SubLandSnapshot candidate, long expectedStructure) {
                Objects.requireNonNull(actor, "actor");
                Objects.requireNonNull(parent, "parent");
                Objects.requireNonNull(candidate, "candidate");
                SelectionSession session = SelectionSession.initial(
                        actor, parent.worldId(), SelectionMode.CREATE_SUBLAND,
                        Optional.of(parent.id()), Optional.empty(),
                        Optional.empty(), Optional.empty(), expectedStructure, clock.instant());
                SubLandConfirmService.Accepted accepted = new SubLandConfirmService.Accepted(
                        session, parent.id(), Optional.empty(), expectedStructure);
                return runner.create(actor, accepted, candidate);
            }

            @Override
            public CompletionStage<Void> delete(
                    UUID actor, LandSnapshot parent, SubLandId target, long expectedStructure) {
                Objects.requireNonNull(actor, "actor");
                Objects.requireNonNull(parent, "parent");
                Objects.requireNonNull(target, "target");
                SelectionSession session = SelectionSession.initial(
                        actor, parent.worldId(), SelectionMode.CREATE_SUBLAND,
                        Optional.of(parent.id()), Optional.of(target),
                        Optional.empty(), Optional.empty(), expectedStructure, clock.instant());
                SubLandConfirmService.Accepted accepted = new SubLandConfirmService.Accepted(
                        session, parent.id(), Optional.of(target), expectedStructure);
                return runner.delete(actor, accepted, target);
            }
        };
    }

    /**
     * Permission changes through the direct-trust and binding services.
     *
     * @param direct land-scoped trust, untrust, defaults and bans; never {@code null}
     * @param bindings land- and subland-scoped generic bindings; never {@code null}
     */
    public static PermissionWritePort permissions(
            LandAuthorisationService direct, LandBindingService bindings) {
        Objects.requireNonNull(direct, "direct");
        Objects.requireNonNull(bindings, "bindings");
        return (request, scopeParent) -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(scopeParent, "scopeParent");
            UUID actor = request.actorUuid();
            return switch (request.changeKind()) {
                case TRUST -> direct.trust(actor, requireLand(request), requireSubject(request));
                case UNTRUST -> direct.untrust(actor, requireLand(request), requireSubject(request));
                case BAN -> direct.ban(actor, requireLand(request), requireSubject(request));
                case UNBAN -> direct.unban(actor, requireLand(request), requireSubject(request));
                case DEFAULT_SET -> {
                    LandAuthorisationSnapshot live = direct.cache().snapshot();
                    if (!live.loaded()) {
                        throw new IllegalStateException("authorisation snapshot is not loaded");
                    }
                    LandId landId = requireLand(request);
                    PermissionState current = live.landDefault(landId, request.action());
                    // Pin the caller-observed generation, not the live
                    // one: the conditional transaction aborts when the
                    // durable row moved after the caller read it, so a
                    // concurrent change landing in the pre-check-to-commit
                    // window fails instead of silently winning. The value
                    // pin stays live-read — every default write bumps the
                    // generation, so a matching generation implies a
                    // matching value.
                    long expectedRevision = request.expectedVersion().requireLandPolicyRevision();
                    yield direct.setDefaultExpected(
                            actor, landId, request.action(), current, expectedRevision,
                            request.state());
                }
                case BIND -> {
                    LandBindingRepository.Subject subject = bindingSubject(request);
                    if (request.landId() != null) {
                        yield bindings.bindLand(
                                actor, request.landId(), subject, request.profileId())
                                .thenApply(ignored -> null);
                    }
                    yield bindings.bindSubland(
                            actor, request.subLandId(), subject, request.profileId())
                            .thenApply(ignored -> null);
                }
                case UNBIND -> {
                    LandBindingRepository.Subject subject = bindingSubject(request);
                    if (request.landId() != null) {
                        yield bindings.unbindLand(actor, request.landId(), subject)
                                .thenApply(ignored -> null);
                    }
                    yield bindings.unbindSubland(actor, request.subLandId(), subject)
                            .thenApply(ignored -> null);
                }
            };
        };
    }

    /**
     * Per-land environment rules through the conditional rule write.
     *
     * @param rules conditional rule service; never {@code null}
     */
    public static RuleWritePort rules(LandRuleMutationService rules) {
        Objects.requireNonNull(rules, "rules");
        return (actor, landId, rule, state, expectedRevision) -> {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(landId, "landId");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(state, "state");
            LandAuthorisationSnapshot live = rules.cache().snapshot();
            if (!live.loaded()) {
                throw new IllegalStateException("authorisation snapshot is not loaded");
            }
            PermissionState current = live.landRule(landId, rule);
            long revision = live.policyRevision(landId).orElse(0L);
            if (revision != expectedRevision) {
                throw new com.smile.chunkland.persistence.StaleAuthorisationException(
                        "authorisation for " + landId + " lapsed: expected revision "
                                + expectedRevision + ", durable " + revision);
            }
            return rules.setRuleExpected(actor, landId, rule, current, revision, state);
        };
    }

    private static LandId requireLand(TypedMutationRequest.PermissionChange request) {
        if (request.landId() == null) {
            throw new IllegalArgumentException(
                    request.changeKind() + " is land scoped but carries no landId");
        }
        return request.landId();
    }

    private static UUID requireSubject(TypedMutationRequest.PermissionChange request) {
        if (request.subjectKind() != PermissionChangedEvent.SubjectKind.PLAYER) {
            throw new IllegalArgumentException(
                    request.changeKind() + " carries no player subject");
        }
        return request.subjectId();
    }

    private static LandBindingRepository.Subject bindingSubject(
            TypedMutationRequest.PermissionChange request) {
        LandBindingRepository.SubjectKind kind =
                request.subjectKind() == PermissionChangedEvent.SubjectKind.GROUP
                        ? LandBindingRepository.SubjectKind.GROUP
                        : LandBindingRepository.SubjectKind.PLAYER;
        return new LandBindingRepository.Subject(kind, request.subjectId());
    }
}
