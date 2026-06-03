// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.AttributeMapper;
import com.ldapportal.ldap.replication.DnMapper;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.ReplicationScopeFilter;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure comparison of a replication link's source and target subtrees.
 * No LDAP or DB I/O — the caller supplies the already-read entries, so
 * the classification logic is unit-testable in isolation.
 *
 * <p>Source is authoritative: the expected target state is the source
 * state mapped through {@link DnMapper} + {@link AttributeMapper}, minus
 * the {@link #EXCLUDED_ATTRS exclusion set}. Three discrepancy classes
 * are produced (see {@link ReconciliationFindingType}); findings shadowed
 * by an undelivered replication event are suppressed so reconciliation
 * never races the live queue.
 */
public final class ReconciliationDiffer {

    private ReconciliationDiffer() {}

    /**
     * Attributes never compared. {@code userpassword} can't be read back
     * from most targets (so drift is unobservable); the rest are
     * server-maintained operational attributes that legitimately differ.
     */
    public static final Set<String> EXCLUDED_ATTRS = Set.of(
            "userpassword",
            "createtimestamp", "modifytimestamp", "creatorsname", "modifiersname",
            "entryuuid", "entrydn", "entrycsn", "hassubordinates", "subschemasubentry",
            "pwdchangedtime", "pwdaccountlockedtime", "structuralobjectclass",
            "whencreated", "whenchanged", "usnchanged", "usncreated", "objectguid");

    /** Outcome of a diff: surviving findings plus the run-summary counts. */
    public record DiffResult(
            List<FindingCandidate> findings,
            int sourceCount,
            int targetCount,
            int missingCount,
            int driftCount,
            int extraCount,
            int suppressedCount) {}

    /**
     * Whole-subtree in-memory diff. The production path is the streaming
     * {@link ChecksumReconciler} (which reuses this class's
     * {@link #computeDrift}, {@link #stripExcluded} and {@link #normDn}
     * helpers); this method is retained deliberately as the <em>reference
     * oracle</em> that {@code ChecksumReconcilerTest#parityWithPureDiffer}
     * checks the streaming path against — not dead code. Keep its
     * classification semantics in lock-step with {@code ChecksumReconciler}.
     */
    public static DiffResult diff(ReplicationLinkSnapshot link,
                                  String targetBaseDn,
                                  List<ReconEntry> sourceEntries,
                                  List<ReconEntry> targetEntries,
                                  Set<String> undeliveredTargetDnsNormalized,
                                  ReconcileDeleteAction deleteAction) {
        return diff(link, targetBaseDn, sourceEntries, targetEntries,
                undeliveredTargetDnsNormalized, deleteAction, ValueNormalizer.DEFAULT);
    }

    /**
     * Whole-subtree diff that compares attribute values through
     * {@code normalizer} (the target schema's matching rules) so case- and
     * DN-formatting-only differences are not reported as drift.
     */
    public static DiffResult diff(ReplicationLinkSnapshot link,
                                  String targetBaseDn,
                                  List<ReconEntry> sourceEntries,
                                  List<ReconEntry> targetEntries,
                                  Set<String> undeliveredTargetDnsNormalized,
                                  ReconcileDeleteAction deleteAction,
                                  ValueNormalizer normalizer) {
        Map<String, ReconEntry> targetByDn = new HashMap<>();
        for (ReconEntry t : targetEntries) targetByDn.put(normDn(t.dn()), t);

        Set<String> expectedTargetDns = new HashSet<>();
        // Protect-set (§7B.4): target DNs of excluded source entries — present
        // but invisible, so never proposed EXTRA/DELETE. Matched on source attrs.
        Set<String> excludedTombstones = new HashSet<>();
        List<FindingCandidate> raw = new ArrayList<>();

        // ── source-driven: MISSING_IN_TARGET + ATTRIBUTE_DRIFT ──────────────
        for (ReconEntry src : sourceEntries) {
            String targetDn = DnMapper.map(src.dn(), link);
            if (targetDn == null) continue;        // out of scope for this link
            String normTarget = normDn(targetDn);
            if (ReplicationScopeFilter.isExcluded(link, src.dn(), src.attributes())) {
                excludedTombstones.add(normTarget);   // present-but-excluded; no expected ADD/MODIFY
                continue;
            }
            expectedTargetDns.add(normTarget);

            Map<String, List<String>> expected =
                    stripExcluded(AttributeMapper.mapAttributes(src.attributes(), link));

            ReconEntry target = targetByDn.get(normTarget);
            if (target == null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("attributes", expected);
                raw.add(new FindingCandidate(ReconciliationFindingType.MISSING_IN_TARGET,
                        ReplicationOperationType.ADD, src.dn(), targetDn, payload));
                continue;
            }
            Map<String, Object> drift = computeDrift(expected, target.attributes(), normalizer);
            if (drift != null) {
                raw.add(new FindingCandidate(ReconciliationFindingType.ATTRIBUTE_DRIFT,
                        ReplicationOperationType.MODIFY, src.dn(), targetDn, drift));
            }
        }

        // ── target-driven: EXTRA_IN_TARGET ──────────────────────────────────
        if (deleteAction != ReconcileDeleteAction.IGNORE) {
            String normBase = targetBaseDn == null ? null : normDn(targetBaseDn);
            for (ReconEntry t : targetEntries) {
                String normDn = normDn(t.dn());
                if (normDn.equals(normBase)) continue;        // never delete the base entry
                if (expectedTargetDns.contains(normDn)) continue;
                if (excludedTombstones.contains(normDn)) continue;   // protected: excluded entry's copy
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("currentTarget", t.attributes());   // for UI / audit only
                raw.add(new FindingCandidate(ReconciliationFindingType.EXTRA_IN_TARGET,
                        ReplicationOperationType.DELETE, null, t.dn(), payload));
            }
        }

        // ── shadow-suppression: drop findings the live queue is converging ──
        List<FindingCandidate> findings = new ArrayList<>(raw.size());
        int suppressed = 0;
        for (FindingCandidate f : raw) {
            if (undeliveredTargetDnsNormalized.contains(normDn(f.targetDn()))) {
                suppressed++;
            } else {
                findings.add(f);
            }
        }

        int missing = (int) findings.stream().filter(f -> f.type() == ReconciliationFindingType.MISSING_IN_TARGET).count();
        int drift   = (int) findings.stream().filter(f -> f.type() == ReconciliationFindingType.ATTRIBUTE_DRIFT).count();
        int extra   = (int) findings.stream().filter(f -> f.type() == ReconciliationFindingType.EXTRA_IN_TARGET).count();
        return new DiffResult(findings, sourceEntries.size(), targetEntries.size(),
                missing, drift, extra, suppressed);
    }

    /**
     * Compare expected (source-authoritative) attrs against the target's
     * current values. Returns a MODIFY payload ({@code modifications} +
     * {@code before}) when any managed attribute differs, else null.
     *
     * <p><b>Asymmetric by design (additive / REPLACE-only):</b> only
     * attributes the <em>source</em> manages are compared. A managed
     * attribute present on the target but not the source is left alone —
     * reconciliation never strips target attributes it didn't put there.
     * Consequently {@link ReconciliationDigest}, which hashes the full
     * managed set on each side, is a <em>conservative</em> pre-filter:
     * equal digests guarantee no drift, but a target-only managed attribute
     * makes the digests differ and forces a (cheap, BASE-scope) pass-2
     * hydration that then finds nothing to correct. That over-hydration is
     * bounded to genuinely out-of-band-modified target entries — under
     * normal operation the target is written only by replication, so its
     * managed attribute set tracks the source.
     */
    static Map<String, Object> computeDrift(Map<String, List<String>> expected,
                                            Map<String, List<String>> targetAttrs) {
        return computeDrift(expected, targetAttrs, ValueNormalizer.DEFAULT);
    }

    /**
     * As {@link #computeDrift(Map, Map)}, but values are compared after
     * canonicalisation through {@code normalizer} (the target schema's
     * matching rules). The MODIFY payload still records the <em>raw</em>
     * source ({@code values}) and target ({@code before}) values so the UI
     * shows the real strings.
     */
    static Map<String, Object> computeDrift(Map<String, List<String>> expected,
                                            Map<String, List<String>> targetAttrs,
                                            ValueNormalizer normalizer) {
        Map<String, List<String>> targetCi = caseInsensitive(targetAttrs);
        List<Map<String, Object>> mods = new ArrayList<>();
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            List<String> want = e.getValue();
            List<String> have = targetCi.get(e.getKey().toLowerCase(Locale.ROOT));
            if (!sameValues(want, have, e.getKey(), normalizer)) {
                Map<String, Object> mod = new LinkedHashMap<>();
                mod.put("type", "REPLACE");
                mod.put("name", e.getKey());
                mod.put("values", want);
                mods.add(mod);
                before.put(e.getKey(), have == null ? List.of() : have);
            }
        }
        if (mods.isEmpty()) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modifications", mods);
        payload.put("before", before);
        return payload;
    }

    static Map<String, List<String>> stripExcluded(Map<String, List<String>> attrs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
            if (!EXCLUDED_ATTRS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Map<String, List<String>> caseInsensitive(Map<String, List<String>> attrs) {
        Map<String, List<String>> ci = new HashMap<>();
        for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
            ci.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return ci;
    }

    /**
     * Order-independent value-set comparison under the attribute's matching
     * rule: each value is canonicalised through {@code normalizer} before the
     * set comparison, so case- and DN-formatting-only differences are equal.
     */
    private static boolean sameValues(List<String> a, List<String> b,
                                      String attrName, ValueNormalizer normalizer) {
        return canonicalSet(a, attrName, normalizer).equals(canonicalSet(b, attrName, normalizer));
    }

    private static Set<String> canonicalSet(List<String> values, String attrName, ValueNormalizer normalizer) {
        if (values == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(normalizer.canonical(attrName, v));
        return out;
    }

    /** Canonical DN for keying / comparison; falls back to lower-case on parse failure. */
    static String normDn(String dn) {
        if (dn == null) return "";
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException ex) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
