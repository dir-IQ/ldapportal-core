// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.AttributeMapper;
import com.ldapportal.ldap.replication.DnMapper;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
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
            List<ReconciliationFinding> findings,
            int sourceCount,
            int targetCount,
            int missingCount,
            int driftCount,
            int extraCount,
            int suppressedCount) {}

    public static DiffResult diff(ReplicationLinkSnapshot link,
                                  String targetBaseDn,
                                  List<ReconEntry> sourceEntries,
                                  List<ReconEntry> targetEntries,
                                  Set<String> undeliveredTargetDnsNormalized,
                                  ReconcileDeleteAction deleteAction) {
        Map<String, ReconEntry> targetByDn = new HashMap<>();
        for (ReconEntry t : targetEntries) targetByDn.put(normDn(t.dn()), t);

        Set<String> expectedTargetDns = new HashSet<>();
        List<ReconciliationFinding> raw = new ArrayList<>();

        // ── source-driven: MISSING_IN_TARGET + ATTRIBUTE_DRIFT ──────────────
        for (ReconEntry src : sourceEntries) {
            String targetDn = DnMapper.map(src.dn(), link);
            if (targetDn == null) continue;        // out of scope for this link
            String normTarget = normDn(targetDn);
            expectedTargetDns.add(normTarget);

            Map<String, List<String>> expected =
                    stripExcluded(AttributeMapper.mapAttributes(src.attributes(), link));

            ReconEntry target = targetByDn.get(normTarget);
            if (target == null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("attributes", expected);
                raw.add(new ReconciliationFinding(ReconciliationFindingType.MISSING_IN_TARGET,
                        ReplicationOperationType.ADD, src.dn(), targetDn, payload));
                continue;
            }
            Map<String, Object> drift = computeDrift(expected, target.attributes());
            if (drift != null) {
                raw.add(new ReconciliationFinding(ReconciliationFindingType.ATTRIBUTE_DRIFT,
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
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("currentTarget", t.attributes());   // for UI / audit only
                raw.add(new ReconciliationFinding(ReconciliationFindingType.EXTRA_IN_TARGET,
                        ReplicationOperationType.DELETE, null, t.dn(), payload));
            }
        }

        // ── shadow-suppression: drop findings the live queue is converging ──
        List<ReconciliationFinding> findings = new ArrayList<>(raw.size());
        int suppressed = 0;
        for (ReconciliationFinding f : raw) {
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
     */
    private static Map<String, Object> computeDrift(Map<String, List<String>> expected,
                                                     Map<String, List<String>> targetAttrs) {
        Map<String, List<String>> targetCi = caseInsensitive(targetAttrs);
        List<Map<String, Object>> mods = new ArrayList<>();
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            List<String> want = e.getValue();
            List<String> have = targetCi.get(e.getKey().toLowerCase(Locale.ROOT));
            if (!sameValues(want, have)) {
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

    private static Map<String, List<String>> stripExcluded(Map<String, List<String>> attrs) {
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

    /** Order-independent value-set comparison. */
    private static boolean sameValues(List<String> a, List<String> b) {
        Set<String> sa = a == null ? Set.of() : new HashSet<>(a);
        Set<String> sb = b == null ? Set.of() : new HashSet<>(b);
        return sa.equals(sb);
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
