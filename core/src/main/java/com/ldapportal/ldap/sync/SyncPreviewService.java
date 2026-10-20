// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewEntry;
import com.ldapportal.dto.sync.SyncPreviewPage;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-entry reconcile preview: for every entry a reconcile of the set would
 * touch, what it would do — add, modify (with the attribute changes), move,
 * delete, hold for review — computed by dry-running the engine's own
 * {@link RecomputeEngine#previewApply decision path}, so the prediction can't
 * drift from the real behaviour. Nothing is written.
 *
 * <p>Each request plans the whole set (one source scan, as a reconcile does),
 * orders it by target DN with parents before children, and dry-runs only the
 * requested page, so target reads scale with the page size, not the set. With
 * {@code changesOnly}, entries the engine is certain to leave alone are dropped
 * first — decided from the index alone (applied, same placement, same content
 * hash: the engine's own hash gate), so no target read is needed to filter.
 *
 * <p>DN references (e.g. {@code member}) resolve through the index and then
 * through the placements this preview plans, approximating the end state a
 * reconcile reaches once its closure fan-out re-drives referrers. Works for a
 * disabled set or link (nothing is written).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncPreviewService {

    public static final int MAX_PAGE_SIZE = 200;

    private static final ReferenceResolver NO_RESOLVER = dn -> Optional.empty();

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final MembershipRepository membershipRepo;
    private final MembershipFunction membershipFunction;
    private final MembershipReferenceResolvers referenceResolvers;
    private final MembershipReconciler reconciler;
    private final RecomputeEngine engine;

    /**
     * @param page        zero-based page
     * @param size        page size, 1..{@value #MAX_PAGE_SIZE}
     * @param changesOnly leave out entries certain to be UNCHANGED
     * @throws IllegalArgumentException for an out-of-range page or size
     */
    public SyncPreviewPage previewEntries(UUID syncSetId, int page, int size, boolean changesOnly) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        SyncLink link = set == null ? null : syncLinkRepo.findById(set.getLinkId()).orElse(null);
        DirectoryConnection source = link == null ? null : directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = link == null ? null : directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (set == null || link == null || source == null || target == null) {
            return unavailable(page, size, changesOnly, "Sync set, link, or source/target directory is unavailable.");
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (SyncIdentity.attribute(set, strategy) == null) {
            return unavailable(page, size, changesOnly, "The source directory type has no LDAP identity attribute.");
        }

        // ── Plan the whole set, as a reconcile would ──
        List<SearchResultEntry> enumerated;
        boolean complete = true;
        String note = null;
        try {
            enumerated = reconciler.enumerate(set, source, strategy);
        } catch (Exception ex) {
            log.warn("Sync set {}: preview source enumeration failed: {}", syncSetId, ex.toString());
            enumerated = List.of();
            complete = false;
            note = "Source enumeration failed (" + ex.getMessage() + "); no deletes would happen.";
        }

        Map<String, Membership> rows = new HashMap<>();
        for (Membership m : membershipRepo.findAllBySyncSetId(set.getId())) {
            rows.put(m.getIdentity(), m);
        }
        List<Item> items = new ArrayList<>();
        Map<String, String> plannedPlacement = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int identified = 0;
        int deletes = 0;
        for (SearchResultEntry e : enumerated) {
            var placement = membershipFunction.evaluate(set, strategy, e, NO_RESOLVER);
            String identity = placement.identity();
            if (identity == null || identity.isBlank()) {
                continue; // a reconcile can't track an entry without an identity either
            }
            identified++;
            seen.add(identity);
            Membership row = rows.get(identity);
            if (placement.member()) {
                plannedPlacement.put(SyncDnUtil.normalize(e.getDN()), placement.targetDn());
            } else if (row != null) {
                deletes++;
            } else {
                continue; // in scope but not in the set, and never synced: a reconcile ignores it
            }
            items.add(new Item(identity, e, placement.member(), placement.targetDn(), row));
        }
        if (complete) {
            for (Membership row : rows.values()) {
                if (!seen.contains(row.getIdentity())) {
                    items.add(new Item(row.getIdentity(), null, false, null, row)); // not seen this scan
                    deletes++;
                }
            }
        }
        MembershipReconciler.Guard guard = MembershipReconciler.guardFor(complete, identified, rows.size(), deletes);

        ReferenceResolver index = referenceResolvers.forLink(link);
        ReferenceResolver resolver = dn -> {
            Optional<String> resolved = index.resolveTargetDn(dn);
            return resolved.isPresent() ? resolved
                    : Optional.ofNullable(plannedPlacement.get(SyncDnUtil.normalize(dn)));
        };

        items.sort(Comparator.comparing(Item::sortKey));
        Integer unchanged = null;
        if (changesOnly) {
            int before = items.size();
            items.removeIf(it -> certainlyUnchanged(it, set, strategy, resolver));
            unchanged = before - items.size();
        }

        // ── Dry-run just the requested page ──
        long total = items.size();
        int totalPages = (int) ((total + size - 1) / size);
        int from = (int) Math.min((long) page * size, total);
        int to = (int) Math.min((long) from + size, total);
        List<SyncPreviewEntry> entries = new ArrayList<>(to - from);
        for (Item it : items.subList(from, to)) {
            entries.add(engine.previewApply(set, target, strategy, it.identity, it.entry, resolver, guard.tripped()));
        }
        return new SyncPreviewPage(entries, page, size, total, totalPages, changesOnly,
                identified, rows.size(), unchanged, guard.tripped(), guard.reason(), complete, note);
    }

    /**
     * True only when the engine would certainly do nothing, decided without
     * touching the target: an applied entry at the same placement with the same
     * content hash (the engine's hash gate, which skips target I/O). Anything
     * else goes through the dry run.
     */
    private boolean certainlyUnchanged(Item it, SyncSet set, IdentityStrategy strategy, ReferenceResolver resolver) {
        if (!it.member) {
            return false; // a tracked entry leaving the set: delete, review or forget
        }
        Membership row = it.row;
        if (row == null || row.getState() != MembershipState.APPLIED
                || row.getTargetDn() == null || !row.getTargetDn().equalsIgnoreCase(it.targetDn)) {
            return false;
        }
        var decision = membershipFunction.evaluate(set, strategy, it.entry, resolver);
        return decision.member() && Arrays.equals(decision.contentHash(), row.getContentHash());
    }

    private static SyncPreviewPage unavailable(int page, int size, boolean changesOnly, String note) {
        return new SyncPreviewPage(List.of(), page, size, 0, 0, changesOnly, 0, 0,
                changesOnly ? 0 : null, false, null, false, note);
    }

    /**
     * Orders DNs so a parent sorts directly before its subtree: compare RDNs from
     * the root down. A reconcile can create entries in this order.
     */
    static String hierarchyKey(String dn) {
        if (dn == null) {
            return "";
        }
        try {
            RDN[] rdns = new DN(dn).getRDNs();
            StringBuilder sb = new StringBuilder(dn.length() + rdns.length);
            for (int i = rdns.length - 1; i >= 0; i--) {
                sb.append(rdns[i].toNormalizedString()).append('\u0001');
            }
            return sb.toString();
        } catch (LDAPException e) {
            return dn.toLowerCase(Locale.ROOT);
        }
    }

    private record Item(String identity, SearchResultEntry entry, boolean member, String targetDn, Membership row) {
        String sortKey() {
            String dn = member ? targetDn
                    : (row != null && row.getTargetDn() != null ? row.getTargetDn()
                    : (entry != null ? entry.getDN() : identity));
            return hierarchyKey(dn);
        }
    }
}
