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

    private void processOnce(SyncSet set, SyncLink link, DirectoryConnection source,
                             DirectoryConnection target, IdentityStrategy strategy, String key) {
        // ── Resolve the current source entry and the identity ──
        Entry entry;
        String identity;
        if (SyncDnUtil.isDn(key)) {
            entry = readSourceByDn(source, key, strategy);
            identity = entry != null
                    ? strategy.extract(entry)
                    : membershipRepo.findFirstBySyncSetIdAndSourceDn(set.getId(), SyncDnUtil.normalize(key))
                            .map(Membership::getIdentity).orElse(null);
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

    /** @return true when the target was actually changed (drives closure). */
    private boolean apply(SyncSet set, DirectoryConnection target, String identity,
                          Membership current, MembershipDecision decision, Entry entry) {
        if (!decision.member()) {
            if (current == null) {
                return false; // (OUT, none) — no-op
            }
            if (set.getDeletePolicy() == SyncDeletePolicy.REVIEW) {
                log.info("Sync set {}: identity {} left membership; deletePolicy=REVIEW, target retained",
                        set.getId(), identity);
                return false;
            }
            ResultCode rc = targetDelete(target, current.getTargetDn());
            if (LdapResultInterpreter.deleteConverged(rc)) {
                membershipRepo.deleteById(new MembershipId(set.getId(), identity));
                return true;
            }
            markFailed(set, identity, current.getSourceDn(), current.getTargetDn(),
                    current.getContentHash(), "delete failed: " + rc);
            return false;
        }

        String targetDn = decision.targetDn();
        byte[] hash = decision.contentHash();
        String sourceDn = SyncDnUtil.normalize(entry.getDN());

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

        Entry tgt = readTarget(target, targetDn);
        ResultCode rc;
        if (tgt == null) {
            rc = targetAdd(target, targetDn, decision.desiredAttrs());
            if (LdapResultInterpreter.addNeedsModify(rc)) {
                tgt = readTarget(target, targetDn);
                rc = (tgt == null) ? rc
                        : targetModify(target, targetDn, TargetEntryDiffer.diff(tgt, decision.desiredAttrs()));
            }
        } else {
            List<Modification> mods = TargetEntryDiffer.diff(tgt, decision.desiredAttrs());
            rc = mods.isEmpty() ? ResultCode.SUCCESS : targetModify(target, targetDn, mods);
        }

        if (!LdapResultInterpreter.success(rc)) {
            markFailed(set, identity, sourceDn, targetDn, hash, "apply failed: " + rc);
            return false;
        }
        upsert(set, identity, sourceDn, targetDn, hash, MembershipState.APPLIED, null, current);
        return true;
    }

    // ── Index persistence ──────────────────────────────────────────────────────

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

    private ReferenceResolver resolverFor(SyncLink link) {
        List<UUID> setIds = syncSetRepo.findAllByLinkId(link.getId()).stream()
                .map(SyncSet::getId).toList();
        return srcDn -> membershipRepo
                .findFirstBySyncSetIdInAndSourceDn(setIds, SyncDnUtil.normalize(srcDn))
                .map(Membership::getTargetDn);
    }

    // ── LDAP helpers (all reads/writes are uncaptured) ──────────────────────────

    private Entry readSourceByDn(DirectoryConnection source, String dn, IdentityStrategy strategy) {
        String idAttr = strategy.identityAttribute();
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
        String idAttr = strategy.identityAttribute();
        if (idAttr == null) {
            return null;
        }
        String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
        SearchScope scope = switch (set.getObjectScope() == null
                ? com.ldapportal.entity.enums.SyncScope.SUB : set.getObjectScope()) {
            case BASE -> SearchScope.BASE;
            case ONE -> SearchScope.ONE;
            case SUB -> SearchScope.SUB;
        };
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

    private ResultCode targetAdd(DirectoryConnection target, String dn, List<Attribute> attrs) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return conn.add(new AddRequest(dn, attrs)).getResultCode();
            } catch (LDAPException e) {
                return e.getResultCode();
            }
        });
    }

    private ResultCode targetModify(DirectoryConnection target, String dn, List<Modification> mods) {
        if (mods.isEmpty()) {
            return ResultCode.SUCCESS;
        }
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return conn.modify(new ModifyRequest(dn, mods)).getResultCode();
            } catch (LDAPException e) {
                return e.getResultCode();
            }
        });
    }

    private ResultCode targetDelete(DirectoryConnection target, String dn) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                return conn.delete(new DeleteRequest(dn)).getResultCode();
            } catch (LDAPException e) {
                return e.getResultCode();
            }
        });
    }

    private ResultCode targetModifyDn(DirectoryConnection target, String oldDn, String newDn) {
        return connectionFactory.withConnectionUnreplicated(target, conn -> {
            try {
                DN newDnParsed = new DN(newDn);
                String newRdn = newDnParsed.getRDNString();
                DN parent = newDnParsed.getParent();
                String newSuperior = parent == null ? null : parent.toString();
                return conn.modifyDN(new ModifyDNRequest(oldDn, newRdn, true, newSuperior)).getResultCode();
            } catch (LDAPException e) {
                return e.getResultCode();
            }
        });
    }
}
