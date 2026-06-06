// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Superadmin CRUD + operations for the sync engine's configuration: links, sync
 * sets, the membership inventory, and operator triggers (reconcile / recompute /
 * resolve a quarantine). Structural validation lives here (DN syntax, filter
 * syntax, distinct endpoints); throws {@link IllegalArgumentException} for
 * 400-class failures and {@link ResourceNotFoundException} for 404.
 */
@Service
@RequiredArgsConstructor
public class SyncConfigService {

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository setRepo;
    private final MembershipRepository membershipRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final MembershipReconciler reconciler;
    private final RecomputeEnqueuer enqueuer;

    // ── Links ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SyncLinkResponse> listLinks() {
        return linkRepo.findAll().stream().map(SyncLinkResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SyncLinkResponse getLink(UUID id) {
        return SyncLinkResponse.of(requireLink(id));
    }

    @Transactional
    public SyncLinkResponse createLink(SyncLinkRequest req) {
        validateLink(req);
        SyncLink l = new SyncLink();
        applyLink(l, req);
        return SyncLinkResponse.of(linkRepo.save(l));
    }

    @Transactional
    public SyncLinkResponse updateLink(UUID id, SyncLinkRequest req) {
        validateLink(req);
        SyncLink l = requireLink(id);
        applyLink(l, req);
        return SyncLinkResponse.of(linkRepo.save(l));
    }

    @Transactional
    public void deleteLink(UUID id) {
        SyncLink l = requireLink(id);
        if (!setRepo.findAllByLinkId(id).isEmpty()) {
            throw new IllegalArgumentException("Delete the link's sync sets before deleting the link");
        }
        linkRepo.delete(l);
    }

    private void applyLink(SyncLink l, SyncLinkRequest req) {
        l.setDisplayName(req.displayName());
        l.setSourceDirId(req.sourceDirId());
        l.setTargetDirId(req.targetDirId());
        l.setEnabled(req.enabled());
        l.setCaptureMode(req.captureMode() != null ? req.captureMode() : SyncCaptureMode.APP_INTERCEPT);
    }

    private void validateLink(SyncLinkRequest req) {
        if (req.sourceDirId().equals(req.targetDirId())) {
            throw new IllegalArgumentException("Source and target directories must differ");
        }
        if (!directoryRepo.existsById(req.sourceDirId())) {
            throw new IllegalArgumentException("Source directory not found");
        }
        if (!directoryRepo.existsById(req.targetDirId())) {
            throw new IllegalArgumentException("Target directory not found");
        }
    }

    // ── Sync sets ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SyncSetResponse> listSets(UUID linkId) {
        List<SyncSet> sets = linkId != null ? setRepo.findAllByLinkId(linkId) : setRepo.findAll();
        return sets.stream().map(SyncSetResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SyncSetResponse getSet(UUID id) {
        return SyncSetResponse.of(requireSet(id));
    }

    @Transactional
    public SyncSetResponse createSet(SyncSetRequest req) {
        validateSet(req);
        SyncSet s = new SyncSet();
        applySet(s, req);
        return SyncSetResponse.of(setRepo.save(s));
    }

    @Transactional
    public SyncSetResponse updateSet(UUID id, SyncSetRequest req) {
        validateSet(req);
        SyncSet s = requireSet(id);
        applySet(s, req);
        return SyncSetResponse.of(setRepo.save(s));
    }

    @Transactional
    public void deleteSet(UUID id) {
        SyncSet s = requireSet(id);
        // membership + recompute_request rows cascade via FK ON DELETE CASCADE.
        setRepo.delete(s);
    }

    private void applySet(SyncSet s, SyncSetRequest req) {
        s.setLinkId(req.linkId());
        s.setName(req.name());
        s.setObjectScopeBaseDn(blankToNull(req.objectScopeBaseDn()));
        s.setObjectScope(req.objectScope());
        s.setIdentityKey(blankToNull(req.identityKey()));
        s.setTargetBaseDn(blankToNull(req.targetBaseDn()));
        s.setApplicabilityFilter(blankToNull(req.applicabilityFilter()));
        s.setReferenceAttributes(blankToNull(req.referenceAttributes()));
        s.setSourceAnchorAttribute(blankToNull(req.sourceAnchorAttribute()));
        s.setDeletePolicy(req.deletePolicy() != null ? req.deletePolicy() : SyncDeletePolicy.DELETE);
        s.setTransformRules(req.transformRules());
        s.setReconcileCadenceSeconds(req.reconcileCadenceSeconds());
        s.setEnabled(req.enabled());
    }

    private void validateSet(SyncSetRequest req) {
        if (!linkRepo.existsById(req.linkId())) {
            throw new IllegalArgumentException("Sync link not found");
        }
        requireValidDn(req.objectScopeBaseDn(), "objectScopeBaseDn");
        requireValidDn(req.targetBaseDn(), "targetBaseDn");
        if (req.applicabilityFilter() != null && !req.applicabilityFilter().isBlank()) {
            try {
                Filter.create(req.applicabilityFilter());
            } catch (LDAPException e) {
                throw new IllegalArgumentException("Invalid applicabilityFilter: " + e.getMessage());
            }
        }
    }

    // ── Membership inventory + operator triggers ────────────────────────────────

    @Transactional(readOnly = true)
    public List<MembershipResponse> listMemberships(UUID syncSetId, MembershipState state) {
        requireSet(syncSetId);
        return membershipRepo.findAllBySyncSetId(syncSetId).stream()
                .filter(m -> state == null || m.getState() == state)
                .map(MembershipResponse::of)
                .toList();
    }

    /** Run a synchronous reconcile of the set; returns the number of source identities enumerated. */
    public int reconcileNow(UUID syncSetId) {
        requireSet(syncSetId);
        return reconciler.reconcile(syncSetId);
    }

    /** Enqueue a recompute for a key (a source DN or a normalized identity). */
    @Transactional
    public void recompute(UUID syncSetId, String key) {
        requireSet(syncSetId);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key (source DN or identity) is required");
        }
        enqueuer.enqueue(syncSetId, key.trim(), null);
    }

    /** Dismiss a quarantined (or any) membership row — drop the index entry without touching the target. */
    @Transactional
    public void dismissMembership(UUID syncSetId, String identity) {
        requireSet(syncSetId);
        membershipRepo.findById(new com.ldapportal.entity.MembershipId(syncSetId, identity))
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
        membershipRepo.deleteById(new com.ldapportal.entity.MembershipId(syncSetId, identity));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SyncLink requireLink(UUID id) {
        return linkRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sync link not found"));
    }

    private SyncSet requireSet(UUID id) {
        return setRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sync set not found"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static void requireValidDn(String dn, String field) {
        if (dn == null || dn.isBlank()) {
            return;
        }
        try {
            new DN(dn);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid " + field + " DN: " + e.getMessage());
        }
    }
}
