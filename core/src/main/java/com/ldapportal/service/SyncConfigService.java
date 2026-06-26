// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.ldap.sync.SyncContentVerifier;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.MembershipStateCount;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /** LDAP attribute descriptor (a leading letter, then letters/digits/hyphens). */
    private static final Pattern ATTR_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9-]*$");

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository setRepo;
    private final MembershipRepository membershipRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final MembershipReconciler reconciler;
    private final SyncContentVerifier verifier;
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
        SyncCaptureMode mode = req.captureMode() != null ? req.captureMode() : SyncCaptureMode.APP_INTERCEPT;
        l.setDisplayName(req.displayName());
        l.setSourceDirId(req.sourceDirId());
        l.setTargetDirId(req.targetDirId());
        l.setEnabled(req.enabled());
        l.setCaptureMode(mode);
        if (mode == SyncCaptureMode.CHANGELOG) {
            l.setChangelogFormat(req.changelogFormat());
            l.setChangelogBaseDn(req.changelogBaseDn());
        } else {
            // APP_INTERCEPT leaves changelog config null (DB cfg constraint).
            l.setChangelogFormat(null);
            l.setChangelogBaseDn(null);
        }
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
        if (req.captureMode() == SyncCaptureMode.CHANGELOG) {
            if (req.changelogFormat() == null) {
                throw new IllegalArgumentException("changelogFormat is required for CHANGELOG capture");
            }
            if (req.changelogBaseDn() == null || req.changelogBaseDn().isBlank()) {
                throw new IllegalArgumentException("changelogBaseDn is required for CHANGELOG capture");
            }
        }
    }

    // ── Sync sets ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SyncSetResponse> listSets(UUID linkId) {
        List<SyncSet> sets = linkId != null ? setRepo.findAllByLinkId(linkId) : setRepo.findAll();
        Map<UUID, Map<String, Long>> counts = membershipStateCounts();
        return sets.stream()
                .map(s -> SyncSetResponse.of(s, counts.getOrDefault(s.getId(), Map.of())))
                .toList();
    }

    /** Membership counts per set keyed by state name, for the health rollup. */
    private Map<UUID, Map<String, Long>> membershipStateCounts() {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        for (MembershipStateCount c : membershipRepo.countGroupedByState()) {
            counts.computeIfAbsent(c.getSyncSetId(), k -> new HashMap<>())
                    .put(c.getState().name(), c.getCnt());
        }
        return counts;
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
        // Default to the safe REVIEW policy (quarantine deletes) when unspecified.
        s.setDeletePolicy(req.deletePolicy() != null ? req.deletePolicy() : SyncDeletePolicy.REVIEW);
        s.setTransformRules(normalizeTransformRules(req.transformRules()));
        s.setExcludedAttributes(normalizeExcludedAttributes(req.excludedAttributes()));
        s.setReconcileCadenceSeconds(req.reconcileCadenceSeconds());
        s.setEnabled(req.enabled());
    }

    /**
     * Trim/dedupe the excluded-attribute override. {@code null} is preserved
     * (engine defaults apply); a non-null list — including empty after cleaning
     * (operator chose to exclude nothing) — is kept. Case-insensitive dedupe,
     * preserving the operator's casing and order.
     */
    private static List<String> normalizeExcludedAttributes(List<String> names) {
        if (names == null) {
            return null;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> out = new java.util.ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            String trimmed = n.trim();
            if (seen.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                out.add(trimmed);
            }
        }
        return out;
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
        validateTransformRules(req.transformRules());
    }

    /**
     * Validate attribute-mapping rules to the engine's actual capability: a rule
     * keys on a non-blank {@code sourceAttr} (the engine matches first-wins on it,
     * so a duplicate would silently shadow), attribute names look like LDAP
     * descriptors, and {@code valueTemplate} carries no token other than the single
     * {@code ${value}} substitution the engine understands.
     */
    private void validateTransformRules(List<SyncTransformRule> rules) {
        if (rules == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (SyncTransformRule rule : rules) {
            String src = rule.getSourceAttr();
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("Transform rule sourceAttr is required");
            }
            if (!ATTR_NAME.matcher(src.trim()).matches()) {
                throw new IllegalArgumentException("Invalid transform rule sourceAttr: " + src);
            }
            if (!seen.add(src.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Duplicate transform rule for source attribute: " + src.trim());
            }
            String tgt = rule.getTargetAttr();
            if (tgt != null && !tgt.isBlank() && !ATTR_NAME.matcher(tgt.trim()).matches()) {
                throw new IllegalArgumentException("Invalid transform rule targetAttr: " + tgt);
            }
            String tpl = rule.getValueTemplate();
            if (tpl != null && tpl.replace("${value}", "").contains("${")) {
                throw new IllegalArgumentException(
                        "Invalid transform rule valueTemplate (only ${value} is supported): " + tpl);
            }
        }
    }

    /**
     * Trim and prune transform rules to a canonical form for storage: drop rows
     * with a blank {@code sourceAttr}, collapse blank target/template to null (the
     * engine reads null target as "same name" and null/blank template as
     * passthrough), and an empty list to null.
     */
    private static List<SyncTransformRule> normalizeTransformRules(List<SyncTransformRule> rules) {
        if (rules == null) {
            return null;
        }
        List<SyncTransformRule> out = new ArrayList<>();
        for (SyncTransformRule r : rules) {
            if (r.getSourceAttr() == null || r.getSourceAttr().isBlank()) {
                continue;
            }
            out.add(new SyncTransformRule(
                    r.getSourceAttr().trim(),
                    blankToNull(r.getTargetAttr()),
                    blankToNull(r.getValueTemplate())));
        }
        return out.isEmpty() ? null : out;
    }

    // ── Membership inventory + operator triggers ────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MembershipResponse> listMemberships(UUID syncSetId, MembershipState state,
                                                    String q, Pageable pageable) {
        requireSet(syncSetId);
        String term = (q == null || q.isBlank()) ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return membershipRepo.search(syncSetId, state, term, pageable).map(MembershipResponse::of);
    }

    /** Run a synchronous reconcile of the set; returns the number of source identities enumerated. */
    public int reconcileNow(UUID syncSetId) {
        requireSet(syncSetId);
        return reconciler.reconcile(syncSetId);
    }

    /** Dry-run preview of what a reconcile would change (no target writes). */
    public com.ldapportal.dto.sync.SyncReconcilePreview previewReconcile(UUID syncSetId) {
        requireSet(syncSetId);
        return reconciler.preview(syncSetId);
    }

    /**
     * Independent content verification: compares the live source scope against the
     * live target base and flags missing / orphaned / drifted entries, without
     * consulting the membership index. Read-only.
     */
    public com.ldapportal.dto.sync.SyncVerifyResult verifyContents(UUID syncSetId) {
        requireSet(syncSetId);
        com.ldapportal.dto.sync.SyncVerifyResult result = verifier.verify(syncSetId);
        // Cache the drift snapshot so the dashboard can surface it without re-reading
        // both directories. Only persist a complete scan — a partial enumeration
        // would record misleadingly low counts over a previously good snapshot.
        if (result.sourceComplete() && result.targetComplete()) {
            setRepo.recordVerifyResult(syncSetId, java.time.OffsetDateTime.now(),
                    result.missingOnTarget(), result.orphanOnTarget(), result.contentMismatches());
        }
        return result;
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
