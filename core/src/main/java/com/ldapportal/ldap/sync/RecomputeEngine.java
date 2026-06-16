// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

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

    /**
     * Recompute one key (a source DN or a normalized identity) for one sync set.
     * Handles its own apply failures (marking the identity FAILED) and returns
     * normally; only truly unexpected/transient faults propagate so the caller
     * can retry.
     */
    public void process(UUID syncSetId, String key) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null || !set.isEnabled()) {
            return;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || !link.isEnabled()) {
            return;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (source == null || target == null) {
            log.warn("Sync set {}: source or target directory missing; skipping", syncSetId);
            return;
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());

        for (int attempt = 1; ; attempt++) {
            try {
                processOnce(set, link, source, target, strategy, key);
                return;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException race) {
                if (attempt >= MAX_OPTIMISTIC_RETRIES) {
                    log.warn("Sync set {}: giving up recompute of [{}] after {} contended attempts",
                            syncSetId, key, attempt);
                    return;
                }
                log.debug("Sync set {}: recompute of [{}] contended (attempt {}); retrying",
                        syncSetId, key, attempt);
            }
        }
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
     */
    private void processOnce(SyncSet set, SyncLink link, DirectoryConnection source,
                             DirectoryConnection target, IdentityStrategy strategy, String key) {
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
            return;
        }

        Membership current = membershipRepo.findById(new MembershipId(set.getId(), identity)).orElse(null);

        MembershipDecision decision = entry != null
                ? membershipFunction.evaluate(set, strategy, entry, resolverFor(link))
                : MembershipDecision.out(identity);

        String changedSourceDn = entry != null
                ? SyncDnUtil.normalize(entry.getDN())
                : (current != null ? current.getSourceDn() : null);

        boolean changed = apply(set, target, identity, current, decision, entry);
        if (changed && changedSourceDn != null) {
            closureResolver.fanOut(link, changedSourceDn);
        }
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
     * @return {@code true} when the target was actually changed — the signal that
     *         drives {@link ClosureResolver closure} fan-out for referrers.
     */
    private boolean apply(SyncSet set, DirectoryConnection target, String identity,
                          Membership current, MembershipDecision decision, Entry entry) {
        if (!decision.member()) {
            if (current == null) {
                return false; // (OUT, none) — no-op
            }
            if (set.getDeletePolicy() == SyncDeletePolicy.REVIEW) {
                // Quarantine the pending delete so it surfaces in the inventory for
                // an operator decision, rather than silently retaining the target.
                if (current.getState() != MembershipState.REVIEW) {
                    markReview(set, identity, current.getSourceDn(), current.getTargetDn(),
                            current.getContentHash(),
                            "scope-exit held for review (deletePolicy=REVIEW)");
                }
                return false;
            }
            ApplyOutcome del = targetDelete(target, current.getTargetDn());
            if (LdapResultInterpreter.deleteConverged(del.code())) {
                membershipRepo.deleteById(new MembershipId(set.getId(), identity));
                return true;
            }
            markFailed(set, identity, current.getSourceDn(), current.getTargetDn(),
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
                markReview(set, identity, sourceDn, targetDn, hash,
                        "ambiguous: " + matches.size() + " target entries carry sourceAnchor=" + identity);
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
                markReview(set, identity, sourceDn, targetDn, hash,
                        "unanchored entry already at placement DN " + targetDn);
                return false;
            }
        }

        // Hash gate: nothing changed in the projection → no target read/write.
        if (current != null && current.getState() == MembershipState.APPLIED
                && targetDn.equalsIgnoreCase(current.getTargetDn())
                && java.util.Arrays.equals(hash, current.getContentHash())) {
            if (!sourceDn.equalsIgnoreCase(current.getSourceDn())) {
                current.setSourceDn(sourceDn);
                membershipRepo.save(current);
            }
            return false;
        }

        // Placement moved under a stable identity → rename/move the target first.
        if (current != null && current.getTargetDn() != null
                && !targetDn.equalsIgnoreCase(current.getTargetDn())) {
            targetModifyDn(target, current.getTargetDn(), targetDn);
        }

        java.util.Set<String> protectedAttrs = SyncExcludedAttributes.effectiveFor(set);
        Entry tgt = readTarget(target, targetDn);
        ApplyOutcome res;
        if (tgt == null) {
            res = targetAdd(target, targetDn, decision.desiredAttrs());
            if (LdapResultInterpreter.addNeedsModify(res.code())) {
                tgt = readTarget(target, targetDn);
                res = (tgt == null) ? res
                        : targetModify(target, targetDn,
                                TargetEntryDiffer.diff(tgt, decision.desiredAttrs(), protectedAttrs));
            }
        } else {
            res = targetModify(target, targetDn,
                    TargetEntryDiffer.diff(tgt, decision.desiredAttrs(), protectedAttrs));
        }

        if (!LdapResultInterpreter.success(res.code())) {
            markFailed(set, identity, sourceDn, targetDn, hash, describe("apply", res));
            return false;
        }
        upsert(set, identity, sourceDn, targetDn, hash, MembershipState.APPLIED, null, current);
        return true;
    }

    // ── Index persistence ──────────────────────────────────────────────────────
    // Every index write goes through upsert(); markFailed/markReview are thin
    // wrappers that re-read the row, preserve the prior content hash when none is
    // supplied, and log. The state column drives the inventory UI and the next
    // recompute's branch (e.g. REVIEW re-runs brownfield correlation).

    /** Insert-or-update the index row for {@code identity} in the given {@code state}. */
    private void upsert(SyncSet set, String identity, String sourceDn, String targetDn,
                        byte[] hash, MembershipState state, String failReason, Membership current) {
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

    private ReferenceResolver resolverFor(SyncLink link) {
        List<UUID> setIds = syncSetRepo.findAllByLinkId(link.getId()).stream()
                .map(SyncSet::getId).toList();
        return srcDn -> membershipRepo
                .findFirstBySyncSetIdInAndSourceDn(setIds, SyncDnUtil.normalize(srcDn))
                .map(Membership::getTargetDn);
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
        return connectionFactory.withConnectionUnreplicated(source, conn -> {
            try {
                com.unboundid.ldap.sdk.Filter f = com.unboundid.ldap.sdk.Filter.createEqualityFilter(idAttr, identity);
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
