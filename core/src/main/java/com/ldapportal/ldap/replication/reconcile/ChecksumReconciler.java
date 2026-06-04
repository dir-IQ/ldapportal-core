// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.AttributeMapper;
import com.ldapportal.ldap.replication.DnMapper;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.ReplicationScopeFilter;
import com.ldapportal.ldap.replication.reconcile.ReconciliationDiffer.DiffResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scalable two-pass reconciliation (R-PP1, §16). Streams both subtrees a
 * page at a time, holding only a normalised-DN → digest index per side
 * (O(N) small entries, not O(N) full entries); classifies missing / drift /
 * extra by set ops + digest comparison; then hydrates <em>only</em> the
 * discrepant entries (one BASE read each) to build the corrective payloads.
 *
 * <p>Produces the same {@link DiffResult} shape as the in-memory
 * {@link ReconciliationDiffer}, reusing its payload builders, so the rest of
 * the engine (auto-apply, run summary) is unchanged.
 */
@Component
@RequiredArgsConstructor
public class ChecksumReconciler {

    private final ReconciliationReadOps readOps;

    @Value("${ldapportal.reconciliation.page-size:500}")
    private int pageSize;

    public DiffResult reconcile(ReplicationLinkSnapshot link,
                                String sourceBase,
                                String targetBase,
                                Set<String> undeliveredTargetDnsNormalized,
                                ReconcileDeleteAction deleteAction) {

        // Compare values through the TARGET schema's matching rules (both the
        // source-mapped "expected" and the actual target attrs live in the
        // target namespace), so case- and DN-formatting-only differences are
        // not mis-classified as drift. Best-effort: falls back to a
        // case-insensitive default if the target schema can't be read.
        ValueNormalizer norm = readOps.readSchema(link.targetDirectory())
                .map(ValueNormalizer::forSchema)
                .orElse(ValueNormalizer.DEFAULT);

        // ── Pass 1: stream both sides into compact digest indexes ───────────
        Map<String, String> expectedDigest = new HashMap<>();   // normTargetDn -> digest
        Map<String, String> sourceDnOf     = new HashMap<>();   // normTargetDn -> source DN (to re-read)
        Map<String, String> targetDnFor    = new HashMap<>();   // normTargetDn -> original (mapped) target DN
        // Protect-set (§7B.4): mapped target DNs of source entries the exclude
        // filter hides. They contribute no expected ADD/MODIFY, but their target
        // copies must NOT be classified EXTRA (and deleted) — "excluded ⇒
        // invisible", not "delete it". Filter is matched on SOURCE attributes.
        Set<String> excludedTombstones = new HashSet<>();
        int[] sourceCount = {0};
        readOps.streamSubtree(link.sourceDirectory(), sourceBase, pageSize, src -> {
            sourceCount[0]++;
            String targetDn = DnMapper.map(src.dn(), link);
            if (targetDn == null) return;                       // out of scope for this link
            String n = ReconciliationDiffer.normDn(targetDn);
            if (ReplicationScopeFilter.isExcluded(link, src.dn(), src.attributes())) {
                excludedTombstones.add(n);                      // present-but-excluded
                return;
            }
            expectedDigest.put(n, ReconciliationDigest.digest(AttributeMapper.mapAttributes(src.attributes(), link), norm));
            sourceDnOf.put(n, src.dn());
            targetDnFor.put(n, targetDn);
        });

        Map<String, String> actualDigest = new HashMap<>();     // normTargetDn -> digest
        Map<String, String> targetDnOf   = new HashMap<>();     // normTargetDn -> original target DN
        int[] targetCount = {0};
        readOps.streamSubtree(link.targetDirectory(), targetBase, pageSize, t -> {
            targetCount[0]++;
            String n = ReconciliationDiffer.normDn(t.dn());
            actualDigest.put(n, ReconciliationDigest.digest(t.attributes(), norm));
            targetDnOf.put(n, t.dn());
        });

        // ── Classify by set ops + digest comparison ─────────────────────────
        List<String> missing = new ArrayList<>();
        List<String> drift   = new ArrayList<>();
        for (Map.Entry<String, String> e : expectedDigest.entrySet()) {
            String actual = actualDigest.get(e.getKey());
            if (actual == null) missing.add(e.getKey());
            else if (!actual.equals(e.getValue())) drift.add(e.getKey());
        }
        List<String> extra = new ArrayList<>();
        String normBase = targetBase == null ? null : ReconciliationDiffer.normDn(targetBase);
        if (deleteAction != ReconcileDeleteAction.IGNORE) {
            for (String n : targetDnOf.keySet()) {
                if (n.equals(normBase)) continue;               // never delete the base entry
                if (excludedTombstones.contains(n)) continue;   // protected: excluded source entry's copy
                if (!expectedDigest.containsKey(n)) extra.add(n);
            }
        }

        // ── Shadow-suppression before hydration (don't re-fetch suppressed) ──
        int suppressed = removeSuppressed(missing, undeliveredTargetDnsNormalized)
                + removeSuppressed(drift, undeliveredTargetDnsNormalized)
                + removeSuppressed(extra, undeliveredTargetDnsNormalized);

        // ── Pass 2: hydrate only the discrepancies ──────────────────────────
        List<FindingCandidate> findings = new ArrayList<>();
        for (String n : missing) {
            ReconEntry src = readOps.readEntry(link.sourceDirectory(), sourceDnOf.get(n)).orElse(null);
            if (src == null) continue;                          // vanished between passes
            Map<String, List<String>> expected =
                    ReconciliationDiffer.stripExcluded(AttributeMapper.mapAttributes(src.attributes(), link));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("attributes", expected);
            findings.add(new FindingCandidate(ReconciliationFindingType.MISSING_IN_TARGET,
                    ReplicationOperationType.ADD, src.dn(), targetDnFor.get(n), payload));
        }
        for (String n : drift) {
            ReconEntry src = readOps.readEntry(link.sourceDirectory(), sourceDnOf.get(n)).orElse(null);
            ReconEntry tgt = readOps.readEntry(link.targetDirectory(), targetDnOf.get(n)).orElse(null);
            if (src == null || tgt == null) continue;
            Map<String, List<String>> expected =
                    ReconciliationDiffer.stripExcluded(AttributeMapper.mapAttributes(src.attributes(), link));
            Map<String, Object> payload = ReconciliationDiffer.computeDrift(expected, tgt.attributes(), norm);
            if (payload == null) continue;                      // digest false-positive — no real drift
            findings.add(new FindingCandidate(ReconciliationFindingType.ATTRIBUTE_DRIFT,
                    ReplicationOperationType.MODIFY, src.dn(), targetDnFor.get(n), payload));
        }
        for (String n : extra) {
            String tdn = targetDnOf.get(n);
            ReconEntry tgt = readOps.readEntry(link.targetDirectory(), tdn).orElse(null);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (tgt != null) payload.put("currentTarget", tgt.attributes());
            findings.add(new FindingCandidate(ReconciliationFindingType.EXTRA_IN_TARGET,
                    ReplicationOperationType.DELETE, null, tdn, payload));
        }

        return new DiffResult(findings, sourceCount[0], targetCount[0],
                countType(findings, ReconciliationFindingType.MISSING_IN_TARGET),
                countType(findings, ReconciliationFindingType.ATTRIBUTE_DRIFT),
                countType(findings, ReconciliationFindingType.EXTRA_IN_TARGET),
                suppressed);
    }

    private static int removeSuppressed(List<String> normDns, Set<String> undelivered) {
        int before = normDns.size();
        normDns.removeIf(undelivered::contains);
        return before - normDns.size();
    }

    private static int countType(List<FindingCandidate> findings, ReconciliationFindingType type) {
        return (int) findings.stream().filter(f -> f.type() == type).count();
    }
}
