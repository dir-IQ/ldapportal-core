// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.ldapportal.dto.sync.SyncPreviewEntry;
import com.ldapportal.dto.sync.SyncReviewKind;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The convergent recompute engine — the one operation in the system. For a
 * {@code (syncSet, key)} it re-reads current source state, computes membership,
 * diffs against the {@link Membership} index, applies the (idempotent) target
 * operation, and commits the index transition. ADD / MODIFY / DELETE / MODDN /
 * scope-enter / scope-exit / attribute-exit are all outcomes of one diff.
 *
 * <p>Convergence makes this tolerant of at-least-once and out-of-order triggers:
 * a duplicate recomputes the same state (no-op via the content-hash gate), and an
 * out-of-order trigger still converges to current source state. Failures isolate
 * to the single identity (its row goes FAILED) and never block other identities.
 *
 * <p>Two-step commit: apply to the target, then commit the index transition. A
 * crash between is healed by the next trigger or reconcile re-deriving and
 * re-applying idempotently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@LdapWriteAuthorized("Sync engine target apply via withConnectionUnreplicated "
        + "(uncaptured so engine writes don't loop back into the recompute queue).")
public class RecomputeEngine {

    private static final int MAX_OPTIMISTIC_RETRIES = 4;

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final MembershipRepository membershipRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final MembershipFunction membershipFunction;
    private final LdapConnectionFactory connectionFactory;
    private final ClosureResolver closureResolver;
    private final MembershipReferenceResolvers referenceResolvers;
    private final SyncTargetSchemas targetSchemas;

    /** The real side effects: target directory writes + index commits. */
    private final Effects live = new LiveEffects();

    /**
     * Recompute one key (a source DN or a normalized identity) for one sync set.
     * Handles its own apply failures (marking the identity FAILED) and returns
     * normally; only truly unexpected/transient faults propagate so the caller
     * can retry.
     */
    public void process(UUID syncSetId, String key) {
        process(syncSetId, key, false);
    }

    /**
     * @param suppressDeletes when true, a scope-exit (OUT) is quarantined for
     *                        REVIEW instead of deleting the target — used by the
     *                        reconciler's blast-radius / zero-enumeration guards
     *                        so a misconfiguration can't mass-delete.
     * @return what the recompute did to the entry (in the preview's vocabulary,
     *         {@code ERROR} for a failed write) and its target DN, or {@code null}
     *         when it was skipped because the set or link is disabled or a
     *         directory is missing
     */
    public RecomputeOutcome process(UUID syncSetId, String key, boolean suppressDeletes) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null || !set.isEnabled()) {
            return null;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || !link.isEnabled()) {
            return null;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (source == null || target == null) {
            log.warn("Sync set {}: source or target directory missing; skipping", syncSetId);
            return null;
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());

        for (int attempt = 1; ; attempt++) {
            try {
                return processOnce(set, link, source, target, strategy, key, suppressDeletes);
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException race) {
                if (attempt >= MAX_OPTIMISTIC_RETRIES) {
                    // Propagate rather than return: a normal return would let the
                    // worker settle (delete) the request as done, silently dropping
                    // the change until the next reconcile. Throwing releases the
                    // claim so the request is retried on the next drain.
                    throw new IllegalStateException("Sync set " + syncSetId + ": recompute of [" + key
                            + "] still contended after " + attempt + " attempts", race);
                }
                log.debug("Sync set {}: recompute of [{}] contended (attempt {}); retrying",
                        syncSetId, key, attempt);
            }
        }
    }

    /**
     * An operator approves a delete held for review: delete the identity's target
     * entry now, whatever the set's delete policy or the reconcile's delete guard
     * said — the operator is the approval. The source is re-read first, so an
     * entry that came back into scope is refused rather than deleted, and the
     * ownership check still applies: a target entry another live identity owns is
     * kept and only this row is dropped.
     *
     * @throws IllegalArgumentException when the set or link is disabled, the row
     *         isn't a held delete, or the entry is back in scope
     */
    public SyncDeleteApproval approveDelete(UUID syncSetId, String identity) {
        SyncSet set = syncSetRepo.findById(syncSetId)
                .orElseThrow(() -> new IllegalArgumentException("Sync set not found"));
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (!set.isEnabled() || link == null || !link.isEnabled()) {
            throw new IllegalArgumentException("Enable the sync set and its link to apply a deletion.");
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (source == null || target == null) {
            throw new IllegalArgumentException("The sync link's source or target directory is missing.");
        }
        Membership current = membershipRepo.findById(new MembershipId(syncSetId, identity)).orElse(null);
        if (current == null
                || SyncReviewKind.of(current.getState(), current.getFailReason()) != SyncReviewKind.PENDING_DELETE) {
            throw new IllegalArgumentException("This entry has no deletion waiting for review.");
        }

        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (SyncIdentity.attribute(set, strategy) == null) {
            // Without an identity attribute the source can't be re-read, and an
            // unconfirmed absence must never become a delete.
            throw new IllegalArgumentException("The source directory type has no LDAP identity attribute.");
        }
        Entry entry = searchSourceByIdentity(source, set, strategy, identity);
        if (entry != null
                && membershipFunction.evaluate(set, strategy, entry, referenceResolvers.forLink(link)).member()) {
            throw new IllegalArgumentException("The source entry is back in scope, so it won't be deleted. "
                    + "Recompute it to sync it again.");
        }

        String targetDn = current.getTargetDn();
        if (!otherOwners(set, target, identity, targetDn).isEmpty()) {
            log.info("Sync set {}: approved delete of {} kept target {}: another identity owns it",
                    syncSetId, identity, targetDn);
            live.dropRow(set, identity);
            return new SyncDeleteApproval(SyncPreviewAction.FORGET, target.getId(), targetDn, null);
        }
        ApplyOutcome del = live.delete(target, targetDn);
        if (!LdapResultInterpreter.deleteConverged(del.code())) {
            String reason = describe("delete", del);
            live.failed(set, identity, current.getSourceDn(), targetDn, current.getContentHash(), reason);
            return new SyncDeleteApproval(SyncPreviewAction.ERROR, target.getId(), targetDn, reason);
        }
        live.dropRow(set, identity);
        if (current.getSourceDn() != null) {
            closureResolver.fanOut(link, current.getSourceDn());
        }
        log.info("Sync set {}: operator-approved delete of target {} (identity {})", syncSetId, targetDn, identity);
        return new SyncDeleteApproval(SyncPreviewAction.DELETE, target.getId(), targetDn, null);
    }

    /**
     * One convergence attempt (the body the optimistic-retry loop wraps).
     *
     * <p>The {@code key} can be either a source DN (from the changelog / app
     * intercept / closure) or an already-normalized identity (from the reconcile
     * not-seen sweep). The first job is to land on the <em>current</em> source
     * {@link Entry} and its stable {@code identity}:
     * <ul>
     *   <li>DN key, entry present → read it, derive identity.</li>
     *   <li>DN key, entry absent but identity is tracked in the index → the entry
     *       likely <b>moved</b> and the feed reported the pre-move DN; re-search by
     *       identity so a rename converges as a MODDN, not a destructive
     *       delete-then-recreate.</li>
     *   <li>identity key → search the source by that identity.</li>
     * </ul>
     * With the (entry, identity, current index row) in hand it evaluates
     * membership and hands off to {@link #apply}; a genuinely absent + never-
     * tracked key is a no-op. On an actual target change it fans out closure for
     * referrers of the changed source DN.
     *
     * @return what was done to the entry ({@code UNCHANGED} for the no-op)
     */
    private RecomputeOutcome processOnce(SyncSet set, SyncLink link, DirectoryConnection source,
                             DirectoryConnection target, IdentityStrategy strategy, String key,
                             boolean suppressDeletes) {
        // ── Resolve the current source entry and the identity ──
        Entry entry;
        String identity;
        if (SyncDnUtil.isDn(key)) {
            entry = readSourceByDn(source, set, key, strategy);
            if (entry != null) {
                identity = SyncIdentity.extract(set, strategy, entry);
            } else {
                identity = membershipRepo.findFirstBySyncSetIdAndSourceDn(set.getId(), SyncDnUtil.normalize(key))
                        .map(Membership::getIdentity).orElse(null);
                // The DN is gone but the identity is tracked — the entry may have
                // MOVED (a feed that reports the pre-move DN, e.g. a changelog
                // modrdn record). Re-search by identity so a rename converges as a
                // MODDN, not a destructive delete+recreate.
                if (identity != null) {
                    entry = searchSourceByIdentity(source, set, strategy, identity);
                }
            }
        } else {
            identity = key;
            entry = searchSourceByIdentity(source, set, strategy, key);
        }
        if (identity == null || identity.isBlank()) {
            // Absent and never tracked — nothing to do.
            return new RecomputeOutcome(SyncPreviewAction.UNCHANGED, null, null);
        }

        Membership current = membershipRepo.findById(new MembershipId(set.getId(), identity)).orElse(null);

        MembershipDecision decision = entry != null
                ? membershipFunction.evaluate(set, strategy, entry, referenceResolvers.forLink(link))
                : MembershipDecision.out(identity);

        String changedSourceDn = entry != null
                ? SyncDnUtil.normalize(entry.getDN())
                : (current != null ? current.getSourceDn() : null);

        LiveRecorder rec = new LiveRecorder();
        boolean changed = apply(set, target, identity, current, decision, entry, suppressDeletes, rec);
        if (changed && changedSourceDn != null) {
            closureResolver.fanOut(link, changedSourceDn);
        }
        String fallbackDn = decision.member() ? decision.targetDn()
                : (current != null ? current.getTargetDn() : null);
        return new RecomputeOutcome(rec.outcome(), rec.outcomeDn != null ? rec.outcomeDn : fallbackDn,
                rec.outcomeReason);
    }

    /**
     * The diff-and-converge core: given the membership {@code decision} and the
     * {@code current} index row, perform the single idempotent target operation
     * that closes the gap, then commit the index transition. Every branch returns
     * to a consistent index state; the two-step (target then index) order means a
     * crash in between is healed by the next idempotent recompute.
     *
     * <p>Decision tree:
     * <ul>
     *   <li><b>OUT</b>: no row → no-op; {@code deletePolicy=REVIEW} → quarantine
     *       the pending delete; otherwise DELETE the target (a NO_SUCH_OBJECT is
     *       treated as already-converged) and drop the index row.</li>
     *   <li><b>IN, brownfield</b>: on a first encounter (or while in REVIEW) with a
     *       configured {@code sourceAnchor}, correlate by anchor to <em>adopt</em> a
     *       pre-existing target rather than duplicate it. Ambiguity (multiple anchor
     *       matches, or an unanchored entry already at the placement DN) quarantines
     *       for REVIEW — never auto-overwrites.</li>
     *   <li><b>IN, hash gate</b>: APPLIED + same placement + same content hash →
     *       no target I/O at all (just refresh a moved source DN). This is the
     *       no-op that makes duplicate/at-least-once triggers cheap and terminates
     *       closure cascades.</li>
     *   <li><b>IN, placement moved</b>: MODDN the target to the new DN first, then
     *       reconcile attributes.</li>
     *   <li><b>IN, apply</b>: read target → ADD (falling through to MODIFY on
     *       ENTRY_ALREADY_EXISTS) or MODIFY via {@link TargetEntryDiffer}; on
     *       success upsert the row APPLIED, on failure mark it FAILED.</li>
     * </ul>
     *
     * <p>Every write — to the target or the index — goes through {@code fx}, so the
     * same decision code runs live ({@link #live}) or as a dry run that records
     * what it would do ({@link #previewApply}); reads always hit the real target.
     *
     * @return {@code true} when the target was actually changed — the signal that
     *         drives {@link ClosureResolver closure} fan-out for referrers.
     */
    private boolean apply(SyncSet set, DirectoryConnection target, String identity,
                          Membership current, MembershipDecision decision, Entry entry,
                          boolean suppressDeletes, Effects fx) {
        if (!decision.member()) {
            if (current == null) {
                return false; // (OUT, none) — no-op
            }
            // Ownership check: never delete a target entry another live identity
            // still owns. The classic case is a source entry deleted and re-created
            // at the same DN (new entryUUID) whose two writes coalesced into one
            // trigger — the new identity adopted the target entry, and this stale
            // row must simply be forgotten, not acted on (nor held for review).
            List<Membership> otherOwners = otherOwners(set, target, identity, current.getTargetDn());
            if (!otherOwners.isEmpty()) {
                log.info("Sync set {}: identity {} left membership but target {} is owned by {} other "
                        + "identity row(s); dropping the stale index row without deleting",
                        set.getId(), identity, current.getTargetDn(), otherOwners.size());
                fx.dropRow(set, identity);
                return false;
            }
            if (suppressDeletes || set.getDeletePolicy() == SyncDeletePolicy.REVIEW) {
                // Quarantine the pending delete so it surfaces in the inventory for
                // an operator decision, rather than deleting. suppressDeletes is the
                // reconciler's blast-radius / zero-enumeration guard tripping.
                if (current.getState() != MembershipState.REVIEW) {
                    fx.review(set, identity, current.getSourceDn(), current.getTargetDn(),
                            current.getContentHash(),
                            suppressDeletes
                                    ? SyncReviewKind.PENDING_DELETE_PREFIX + " (delete guard tripped)"
                                    : SyncReviewKind.PENDING_DELETE_PREFIX + " (deletePolicy=REVIEW)");
                }
                return false;
            }
            ApplyOutcome del = fx.delete(target, current.getTargetDn());
            if (LdapResultInterpreter.deleteConverged(del.code())) {
                fx.dropRow(set, identity);
                return true;
            }
            fx.failed(set, identity, current.getSourceDn(), current.getTargetDn(),
                    current.getContentHash(), describe("delete", del));
            return false;
        }

        String targetDn = decision.targetDn();
        byte[] hash = decision.contentHash();
        String sourceDn = SyncDnUtil.normalize(entry.getDN());

        // ── Brownfield adoption (conservative) ──
        // On a first encounter (or while held in REVIEW) with a configured
        // sourceAnchor, correlate by anchor before deciding ADD vs MODIFY so we
        // adopt a pre-existing target entry rather than duplicate it. Ambiguity —
        // multiple anchor matches, or an unanchored entry already at the
        // placement DN — quarantines for an operator decision, never auto-
        // overwrites.
        String anchorAttr = set.getSourceAnchorAttribute();
        boolean firstOrReview = current == null || current.getState() == MembershipState.REVIEW;
        if (anchorAttr != null && !anchorAttr.isBlank() && firstOrReview) {
            List<String> matches = searchTargetByAnchor(target, set, anchorAttr, identity);
            if (matches.size() > 1) {
                fx.review(set, identity, sourceDn, targetDn, hash,
                        SyncReviewKind.AMBIGUOUS_MATCH_PREFIX + " " + matches.size()
                                + " target entries carry sourceAnchor=" + identity);
                return false;
            } else if (matches.size() == 1) {
                String adoptedDn = matches.get(0);
                if (current == null) {
                    current = synthetic(set, identity, sourceDn, adoptedDn);
                } else {
                    current.setTargetDn(adoptedDn);
                }
            } else if (readTarget(target, targetDn) != null) {
                // No anchor match but the placement DN is occupied by an entry
                // that doesn't carry our anchor → don't clobber it.
                fx.review(set, identity, sourceDn, targetDn, hash,
                        SyncReviewKind.PLACEMENT_CONFLICT_PREFIX + " " + targetDn);
                return false;
            }
        }

        // Hash gate: nothing changed in the projection → no target read/write.
        if (current != null && current.getState() == MembershipState.APPLIED
                && SyncDnUtil.sameDn(targetDn, current.getTargetDn())
                && java.util.Arrays.equals(hash, current.getContentHash())) {
            if (!sourceDn.equalsIgnoreCase(current.getSourceDn())) {
                fx.refreshSourceDn(current, sourceDn);
            }
            return false;
        }

        // Placement moved under a stable identity → rename/move the target first.
        // The outcome matters: if the rename fails and the entry is still at the
        // old DN, stop here and keep the OLD target DN on the row. Falling through
        // to an ADD at the new DN would leave the old entry orphaned with nothing
        // in the index remembering it (the row would only ever name the new DN).
        // A rename that "fails" because the old entry is already gone is fine —
        // the ADD below recreates it, exactly as a crash-recovery replay would.
        // Same DN in another form (case, spacing — e.g. a row written before
        // placement kept the source's RDN case) is not a move.
        if (current != null && current.getTargetDn() != null
                && !SyncDnUtil.sameDn(targetDn, current.getTargetDn())) {
            String oldTargetDn = current.getTargetDn();
            ApplyOutcome mv = fx.modifyDn(target, oldTargetDn, targetDn);
            if (!LdapResultInterpreter.success(mv.code()) && readTarget(target, oldTargetDn) != null) {
                fx.failed(set, identity, sourceDn, oldTargetDn, hash,
                        describe("rename to " + targetDn, mv));
                return false;
            }
        }

        java.util.Set<String> protectedAttrs = SyncExcludedAttributes.effectiveFor(set);
        // DN-valued attributes compare as DNs, so a reference that differs only
        // in form (case, spacing) isn't rewritten; other values compare exactly.
        com.unboundid.ldap.sdk.schema.Schema schema = targetSchemas.forDirectory(target);
        List<String> dnAttrs = SyncReferenceAttributes.forSet(set);
        Entry tgt = fx.readTarget(target, targetDn);
        ApplyOutcome res;
        if (tgt == null) {
            res = fx.add(target, targetDn, decision.desiredAttrs());
            if (LdapResultInterpreter.addNeedsModify(res.code())) {
                tgt = fx.readTarget(target, targetDn);
                res = (tgt == null) ? res
                        : fx.modify(target, targetDn,
                                TargetEntryDiffer.diff(tgt, decision.desiredAttrs(), protectedAttrs, schema, dnAttrs), tgt);
            }
        } else {
            res = fx.modify(target, targetDn,
                    TargetEntryDiffer.diff(tgt, decision.desiredAttrs(), protectedAttrs, schema, dnAttrs), tgt);
        }

        if (!LdapResultInterpreter.success(res.code())) {
            fx.failed(set, identity, sourceDn, targetDn, hash, describe("apply", res));
            return false;
        }
        fx.applied(set, identity, sourceDn, targetDn, hash, current);
        return true;
    }

    // ── Dry run (impact preview) ───────────────────────────────────────────────

    /**
     * Predict what a reconcile would do for one identity, without writing to the
     * target or the index: runs {@link #apply} with a {@link PreviewRecorder}, so
     * every branch — brownfield correlation, the hash gate, ownership checks, the
     * delete policy and guard — is the live code, with live target reads.
     *
     * @param current         the identity's index row, or {@code null} when untracked;
     *                        a detached copy (the adoption branch may change it)
     * @param entry           the current source entry, or {@code null} when it is
     *                        gone from the scope (an OUT, as for a not-seen row)
     * @param resolver        DN-reference resolver for projecting the entry
     * @param suppressDeletes whether the reconcile's delete guard would trip
     */
    public SyncPreviewEntry previewApply(SyncSet set, DirectoryConnection target, IdentityStrategy strategy,
                                         String identity, Membership current, Entry entry,
                                         ReferenceResolver resolver, boolean suppressDeletes) {
        MembershipDecision decision = entry != null
                ? membershipFunction.evaluate(set, strategy, entry, resolver)
                : MembershipDecision.out(identity);
        PreviewRecorder rec = new PreviewRecorder();
        apply(set, target, identity, current, decision, entry, suppressDeletes, rec);
        String sourceDn = entry != null ? entry.getDN() : (current != null ? current.getSourceDn() : null);
        String fallbackDn = decision.member() ? decision.targetDn()
                : (current != null ? current.getTargetDn() : null);
        return rec.toEntry(identity, sourceDn, fallbackDn);
    }

    /**
     * Records {@link #apply}'s writes instead of performing them. Writes report
     * success, so the decision path continues exactly as after a successful write.
     */
    private final class PreviewRecorder implements Effects {
        private String addDn;
        private List<Attribute> addAttrs;
        private String modifyDn;
        private List<SyncPreviewAttributeChange> modifyChanges;
        private String moveFrom;
        private String moveTo;
        private String deleteDn;
        private String reviewDn;
        private String reviewReason;
        private String failedDn;
        private String failedReason;
        private boolean applied;
        private boolean droppedRow;

        @Override
        public Entry readTarget(DirectoryConnection target, String dn) {
            if (moveTo == null || !SyncDnUtil.normalize(dn).equals(SyncDnUtil.normalize(moveTo))) {
                return RecomputeEngine.this.readTarget(target, dn);
            }
            // The move was only recorded: read the entry where it is now and
            // present it as the server would after MODDN (deleteOldRDN=true).
            Entry existing = RecomputeEngine.this.readTarget(target, moveFrom);
            return existing == null ? null : renamed(existing, moveTo);
        }

        @Override
        public ApplyOutcome add(DirectoryConnection target, String dn, List<Attribute> attrs) {
            addDn = dn;
            addAttrs = attrs;
            return ok(ResultCode.SUCCESS);
        }

        @Override
        public ApplyOutcome modify(DirectoryConnection target, String dn, List<Modification> mods, Entry before) {
            if (!mods.isEmpty()) {
                modifyDn = dn;
                modifyChanges = SyncPreviewValues.forModify(before, mods);
            }
            return ok(ResultCode.SUCCESS);
        }

        @Override
        public ApplyOutcome modifyDn(DirectoryConnection target, String oldDn, String newDn) {
            moveFrom = oldDn;
            moveTo = newDn;
            return ok(ResultCode.SUCCESS);
        }

        @Override
        public ApplyOutcome delete(DirectoryConnection target, String dn) {
            deleteDn = dn;
            return ok(ResultCode.SUCCESS);
        }

        @Override
        public void applied(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                            Membership current) {
            applied = true;
        }

        @Override
        public void review(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            reviewDn = targetDn;
            reviewReason = reason;
        }

        @Override
        public void failed(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            // Unreachable while every write reports success; surfaced rather than lost.
            failedDn = targetDn;
            failedReason = reason;
        }

        @Override
        public void dropRow(SyncSet set, String identity) {
            droppedRow = true;
        }

        @Override
        public void refreshSourceDn(Membership current, String sourceDn) {
            // Index bookkeeping only; not a change worth showing.
        }

        SyncPreviewEntry toEntry(String identity, String sourceDn, String fallbackDn) {
            if (deleteDn != null) {
                return entry(identity, sourceDn, deleteDn, null, SyncPreviewAction.DELETE, null, List.of());
            }
            if (reviewReason != null) {
                return entry(identity, sourceDn, reviewDn, null, SyncPreviewAction.REVIEW, reviewReason, List.of());
            }
            if (failedReason != null) {
                return entry(identity, sourceDn, failedDn, null, SyncPreviewAction.ERROR, failedReason, List.of());
            }
            List<SyncPreviewAttributeChange> changes = addAttrs != null
                    ? SyncPreviewValues.forAdd(addAttrs)
                    : (modifyChanges != null ? modifyChanges : List.of());
            if (moveTo != null) {
                return entry(identity, sourceDn, moveTo, moveFrom, SyncPreviewAction.MOVE, null, changes);
            }
            if (addDn != null) {
                return entry(identity, sourceDn, addDn, null, SyncPreviewAction.ADD, null, changes);
            }
            if (modifyDn != null) {
                return entry(identity, sourceDn, modifyDn, null, SyncPreviewAction.MODIFY, null, changes);
            }
            if (applied) {
                return entry(identity, sourceDn, fallbackDn, null, SyncPreviewAction.NO_CHANGE, null, List.of());
            }
            if (droppedRow) {
                return entry(identity, sourceDn, fallbackDn, null, SyncPreviewAction.FORGET,
                        "target entry is owned by another identity; only the stale index row is dropped",
                        List.of());
            }
            return entry(identity, sourceDn, fallbackDn, null, SyncPreviewAction.UNCHANGED, null, List.of());
        }

        private static Entry renamed(Entry existing, String newDn) {
            Entry moved = existing.duplicate();
            try {
                com.unboundid.ldap.sdk.RDN oldRdn = existing.getParsedDN().getRDN();
                com.unboundid.ldap.sdk.RDN newRdn = new DN(newDn).getRDN();
                moved.setDN(newDn);
                if (oldRdn != null) {
                    String[] names = oldRdn.getAttributeNames();
                    String[] vals = oldRdn.getAttributeValues();
                    for (int i = 0; i < names.length; i++) {
                        moved.removeAttributeValue(names[i], vals[i]);
                    }
                }
                if (newRdn != null) {
                    String[] names = newRdn.getAttributeNames();
                    String[] vals = newRdn.getAttributeValues();
                    for (int i = 0; i < names.length; i++) {
                        moved.addAttribute(names[i], vals[i]);
                    }
                }
            } catch (LDAPException e) {
                moved.setDN(newDn); // unparseable DN: attribute values stay as read
            }
            return moved;
        }

        private static SyncPreviewEntry entry(String identity, String sourceDn, String targetDn, String previous,
                                              SyncPreviewAction action, String reason,
                                              List<SyncPreviewAttributeChange> changes) {
            return new SyncPreviewEntry(identity, sourceDn, targetDn, previous, action, reason, changes);
        }
    }

    // ── Side-effect seam ───────────────────────────────────────────────────────

    /**
     * Every write {@link #apply} can make. {@link LiveEffects} performs them;
     * {@link PreviewRecorder} records them and reports success, so a dry run walks
     * exactly the branches a real run would (reads are never redirected).
     */
    private interface Effects {
        /**
         * Read the target entry the apply step diffs against. Routed through the
         * seam because after a (recorded, not performed) MOVE a dry run must read
         * the entry as it would be at its new DN.
         */
        Entry readTarget(DirectoryConnection target, String dn);

        ApplyOutcome add(DirectoryConnection target, String dn, List<Attribute> attrs);

        /** {@code before} is the target entry the modifications were diffed against. */
        ApplyOutcome modify(DirectoryConnection target, String dn, List<Modification> mods, Entry before);

        ApplyOutcome modifyDn(DirectoryConnection target, String oldDn, String newDn);

        ApplyOutcome delete(DirectoryConnection target, String dn);

        void applied(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                     Membership current);

        void review(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash, String reason);

        void failed(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash, String reason);

        void dropRow(SyncSet set, String identity);

        void refreshSourceDn(Membership current, String sourceDn);
    }

    private final class LiveEffects implements Effects {
        @Override
        public Entry readTarget(DirectoryConnection target, String dn) {
            return RecomputeEngine.this.readTarget(target, dn);
        }

        @Override
        public ApplyOutcome add(DirectoryConnection target, String dn, List<Attribute> attrs) {
            return targetAdd(target, dn, attrs);
        }

        @Override
        public ApplyOutcome modify(DirectoryConnection target, String dn, List<Modification> mods, Entry before) {
            return targetModify(target, dn, mods);
        }

        @Override
        public ApplyOutcome modifyDn(DirectoryConnection target, String oldDn, String newDn) {
            return targetModifyDn(target, oldDn, newDn);
        }

        @Override
        public ApplyOutcome delete(DirectoryConnection target, String dn) {
            return targetDelete(target, dn);
        }

        @Override
        public void applied(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                            Membership current) {
            upsert(set, identity, sourceDn, targetDn, hash, MembershipState.APPLIED, null, current);
        }

        @Override
        public void review(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            markReview(set, identity, sourceDn, targetDn, hash, reason);
        }

        @Override
        public void failed(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            markFailed(set, identity, sourceDn, targetDn, hash, reason);
        }

        @Override
        public void dropRow(SyncSet set, String identity) {
            membershipRepo.deleteById(new MembershipId(set.getId(), identity));
        }

        @Override
        public void refreshSourceDn(Membership current, String sourceDn) {
            current.setSourceDn(sourceDn);
            membershipRepo.save(current);
        }
    }

    /**
     * Performs {@link #apply}'s writes through {@link #live} and notes which of
     * them took effect, so a reconcile can report what it did to each entry.
     * One per recompute attempt.
     */
    private final class LiveRecorder implements Effects {
        private boolean added;
        private boolean modified;
        private boolean moved;
        private boolean deleted;
        private boolean held;
        private boolean failed;
        private boolean applied;
        private boolean droppedRow;
        /** The target DN the last effective write or index transition named. */
        private String outcomeDn;
        /** Why the entry was held or failed. */
        private String outcomeReason;

        @Override
        public Entry readTarget(DirectoryConnection target, String dn) {
            return live.readTarget(target, dn);
        }

        @Override
        public ApplyOutcome add(DirectoryConnection target, String dn, List<Attribute> attrs) {
            ApplyOutcome res = live.add(target, dn, attrs);
            if (LdapResultInterpreter.success(res.code())) {
                added = true;
                outcomeDn = dn;
            }
            return res;
        }

        @Override
        public ApplyOutcome modify(DirectoryConnection target, String dn, List<Modification> mods, Entry before) {
            ApplyOutcome res = live.modify(target, dn, mods, before);
            modified |= !mods.isEmpty() && LdapResultInterpreter.success(res.code());
            return res;
        }

        @Override
        public ApplyOutcome modifyDn(DirectoryConnection target, String oldDn, String newDn) {
            ApplyOutcome res = live.modifyDn(target, oldDn, newDn);
            if (LdapResultInterpreter.success(res.code())) {
                moved = true;
                outcomeDn = newDn;
            }
            return res;
        }

        @Override
        public ApplyOutcome delete(DirectoryConnection target, String dn) {
            ApplyOutcome res = live.delete(target, dn);
            if (LdapResultInterpreter.deleteConverged(res.code())) {
                deleted = true;
                outcomeDn = dn;
            }
            return res;
        }

        @Override
        public void applied(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                            Membership current) {
            live.applied(set, identity, sourceDn, targetDn, hash, current);
            applied = true;
            outcomeDn = targetDn;
        }

        @Override
        public void review(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            live.review(set, identity, sourceDn, targetDn, hash, reason);
            held = true;
            outcomeDn = targetDn;
            outcomeReason = reason;
        }

        @Override
        public void failed(SyncSet set, String identity, String sourceDn, String targetDn, byte[] hash,
                           String reason) {
            live.failed(set, identity, sourceDn, targetDn, hash, reason);
            failed = true;
            outcomeDn = targetDn;
            outcomeReason = reason;
        }

        @Override
        public void dropRow(SyncSet set, String identity) {
            live.dropRow(set, identity);
            droppedRow = true;
        }

        @Override
        public void refreshSourceDn(Membership current, String sourceDn) {
            live.refreshSourceDn(current, sourceDn);
        }

        /** Same precedence as {@link PreviewRecorder#toEntry}, so a run reads like its preview. */
        SyncPreviewAction outcome() {
            if (deleted) {
                return SyncPreviewAction.DELETE;
            }
            if (held) {
                return SyncPreviewAction.REVIEW;
            }
            if (failed) {
                return SyncPreviewAction.ERROR;
            }
            if (moved) {
                return SyncPreviewAction.MOVE;
            }
            if (added) {
                return SyncPreviewAction.ADD;
            }
            if (modified) {
                return SyncPreviewAction.MODIFY;
            }
            if (applied) {
                return SyncPreviewAction.NO_CHANGE;
            }
            if (droppedRow) {
                return SyncPreviewAction.FORGET;
            }
            return SyncPreviewAction.UNCHANGED;
        }
    }

    // ── Index persistence ──────────────────────────────────────────────────────
    // Every index write goes through upsert(); markFailed/markReview are thin
    // wrappers that re-read the row, preserve the prior content hash when none is
    // supplied, and log. The state column drives the inventory UI and the next
    // recompute's branch (e.g. REVIEW re-runs brownfield correlation).

    /**
     * Insert-or-update the index row for {@code identity} in the given {@code state}.
     *
     * <p>A target DN has exactly one owner per set (enforced by the unique index on
     * {@code (sync_set_id, target_dn)}), so before this identity is recorded at
     * {@code targetDn} any other row of the set still naming that DN is retired:
     * it is stale memory of a source entry that was deleted, or re-created under a
     * new identity at the same DN, and the target entry now belongs to this one.
     */
    private void upsert(SyncSet set, String identity, String sourceDn, String targetDn,
                        byte[] hash, MembershipState state, String failReason, Membership current) {
        int retired = membershipRepo.retireOtherOwners(set.getId(), targetDn, identity);
        if (retired > 0) {
            log.info("Sync set {}: retired {} stale index row(s) that still named target {} now owned by {}",
                    set.getId(), retired, targetDn, identity);
        }
        Membership m = current != null ? current : new Membership();
        if (current == null) {
            m.setSyncSetId(set.getId());
            m.setIdentity(identity);
        }
        m.setSourceDn(sourceDn);
        m.setTargetDn(targetDn);
        m.setContentHash(hash);
        m.setState(state);
        m.setFailReason(failReason);
        membershipRepo.save(m);
    }

    private void markFailed(SyncSet set, String identity, String sourceDn, String targetDn,
                            byte[] hash, String reason) {
        Membership current = membershipRepo.findById(new MembershipId(set.getId(), identity)).orElse(null);
        byte[] effectiveHash = hash != null ? hash
                : (current != null ? current.getContentHash() : new byte[0]);
        upsert(set, identity, sourceDn, targetDn, effectiveHash, MembershipState.FAILED, reason, current);
        log.warn("Sync set {}: identity {} FAILED — {}", set.getId(), identity, reason);
    }

    private void markReview(SyncSet set, String identity, String sourceDn, String targetDn,
                            byte[] hash, String reason) {
        Membership current = membershipRepo.findById(new MembershipId(set.getId(), identity)).orElse(null);
        byte[] effectiveHash = hash != null ? hash
                : (current != null ? current.getContentHash() : new byte[0]);
        upsert(set, identity, sourceDn, targetDn, effectiveHash, MembershipState.REVIEW, reason, current);
        log.info("Sync set {}: identity {} quarantined for REVIEW — {}", set.getId(), identity, reason);
    }

    /**
     * Index rows other than {@code identity} that still claim {@code targetDn} in
     * any set writing into the same target directory. Non-empty means the target
     * entry is (or is about to be) somebody else's, so an OUT for this identity
     * must not delete it.
     */
    private List<Membership> otherOwners(SyncSet set, DirectoryConnection target,
                                         String identity, String targetDn) {
        if (targetDn == null) {
            return List.of();
        }
        java.util.Set<UUID> setsOnTarget = new java.util.HashSet<>();
        for (SyncLink l : syncLinkRepo.findAllByTargetDirId(target.getId())) {
            for (SyncSet s : syncSetRepo.findAllByLinkId(l.getId())) {
                setsOnTarget.add(s.getId());
            }
        }
        setsOnTarget.add(set.getId());
        List<Membership> owners = new java.util.ArrayList<>();
        for (Membership m : membershipRepo.findAllByTargetDnIgnoreCase(targetDn)) {
            boolean self = m.getSyncSetId().equals(set.getId()) && m.getIdentity().equals(identity);
            if (!self && setsOnTarget.contains(m.getSyncSetId())) {
                owners.add(m);
            }
        }
        return owners;
    }

    /** A transient (unsaved) membership used to adopt an existing target entry. */
    private static Membership synthetic(SyncSet set, String identity, String sourceDn, String adoptedDn) {
        Membership m = new Membership();
        m.setSyncSetId(set.getId());
        m.setIdentity(identity);
        m.setSourceDn(sourceDn);
        m.setTargetDn(adoptedDn);
        m.setContentHash(new byte[0]);
        m.setState(MembershipState.PENDING);
        return m;
    }

    // ── LDAP helpers (all reads/writes are uncaptured) ──────────────────────────

    private Entry readSourceByDn(DirectoryConnection source, SyncSet set, String dn, IdentityStrategy strategy) {
        String idAttr = SyncIdentity.attribute(set, strategy);
        String[] attrs = idAttr != null ? new String[]{"*", idAttr} : new String[]{"*"};
        return connectionFactory.withConnectionUnreplicated(source, conn -> {
            try {
                return conn.getEntry(dn, attrs);
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw e;
            }
        });
    }

    private Entry searchSourceByIdentity(DirectoryConnection source, SyncSet set,
                                         IdentityStrategy strategy, String identity) {
        String idAttr = SyncIdentity.attribute(set, strategy);
        if (idAttr == null) {
            return null;
        }
        String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
        SearchScope scope = SyncScopes.searchScope(set);
        com.unboundid.ldap.sdk.Filter f = SyncIdentity.filter(set, strategy, identity);
        return connectionFactory.withConnectionUnreplicated(source, conn -> {
            try {
                SearchRequest req = new SearchRequest(base, scope, f, "*", idAttr);
                List<SearchResultEntry> entries = conn.search(req).getSearchEntries();
                return entries.isEmpty() ? null : entries.get(0);
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw e;
            }
        });
    }

    private Entry readTarget(DirectoryConnection target, String dn) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return conn.getEntry(dn, "*");
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw e;
            }
        });
    }

    private ApplyOutcome targetAdd(DirectoryConnection target, String dn, List<Attribute> attrs) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return ok(conn.add(new AddRequest(dn, attrs)).getResultCode());
            } catch (LDAPException e) {
                return failed(e);
            }
        });
    }

    /** Target DNs carrying {@code anchorAttr=identity} (brownfield correlation). */
    private List<String> searchTargetByAnchor(DirectoryConnection target, SyncSet set,
                                              String anchorAttr, String identity) {
        String base = set.getTargetBaseDn() != null ? set.getTargetBaseDn() : target.getBaseDn();
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                SearchRequest req = new SearchRequest(base, SearchScope.SUB,
                        com.unboundid.ldap.sdk.Filter.createEqualityFilter(anchorAttr, identity), "1.1");
                List<String> dns = new java.util.ArrayList<>();
                for (SearchResultEntry e : conn.search(req).getSearchEntries()) {
                    dns.add(e.getDN());
                }
                return dns;
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return List.of();
                }
                throw e;
            }
        });
    }

    private ApplyOutcome targetModify(DirectoryConnection target, String dn, List<Modification> mods) {
        if (mods.isEmpty()) {
            return ok(ResultCode.SUCCESS);
        }
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return ok(conn.modify(new ModifyRequest(dn, mods)).getResultCode());
            } catch (LDAPException e) {
                return failed(e);
            }
        });
    }

    private ApplyOutcome targetDelete(DirectoryConnection target, String dn) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return ok(conn.delete(new DeleteRequest(dn)).getResultCode());
            } catch (LDAPException e) {
                return failed(e);
            }
        });
    }

    private ApplyOutcome targetModifyDn(DirectoryConnection target, String oldDn, String newDn) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                DN newDnParsed = new DN(newDn);
                String newRdn = newDnParsed.getRDNString();
                DN parent = newDnParsed.getParent();
                String newSuperior = parent == null ? null : parent.toString();
                return ok(conn.modifyDN(new ModifyDNRequest(oldDn, newRdn, true, newSuperior)).getResultCode());
            } catch (LDAPException e) {
                return failed(e);
            }
        });
    }

    // ── Apply outcome: the result code plus, on failure, the server's diagnostic
    // message, so a FAILED row's reason says *why* (e.g. "…pre-encoded passwords
    // are not allowed…") instead of just the bare code.

    private record ApplyOutcome(ResultCode code, String detail) {
    }

    private static ApplyOutcome ok(ResultCode code) {
        return new ApplyOutcome(code, null);
    }

    private static ApplyOutcome failed(LDAPException e) {
        return new ApplyOutcome(e.getResultCode(), diagnosticOf(e));
    }

    /** A concise, single-line diagnostic from the server (collapsed + length-capped). */
    private static String diagnosticOf(LDAPException e) {
        String msg = e.getDiagnosticMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getMessage();
        }
        if (msg == null || msg.isBlank()) {
            return null;
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 300 ? msg.substring(0, 297) + "…" : msg;
    }

    private static String describe(String verb, ApplyOutcome o) {
        String base = verb + " failed: " + o.code();
        return (o.detail() == null || o.detail().isBlank()) ? base : base + " — " + o.detail();
    }
}
