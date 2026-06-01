// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot.AttrMappingSnapshot;
import com.ldapportal.ldap.replication.reconcile.ReconciliationDiffer.DiffResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure comparison tests — no LDAP, no Spring. Pins the classification
 * contract: missing→ADD, drift→MODIFY, extra→DELETE (gated), the
 * operational-attribute exclusion set, attribute/DN mapping parity with
 * replication, and shadow-suppression.
 */
class ReconciliationDifferTest {

    // Identity-mapping link (source base == target base) with no attr rules.
    private ReplicationLinkSnapshot identityLink() {
        return new ReplicationLinkSnapshot(UUID.randomUUID(), "L",
                null, null, null, null, true, false, List.of());
    }

    private ReplicationLinkSnapshot link(String sourceBase, String targetBase,
                                         List<AttrMappingSnapshot> mappings) {
        return new ReplicationLinkSnapshot(UUID.randomUUID(), "L",
                null, null, sourceBase, targetBase, true, false, mappings);
    }

    private ReconEntry entry(String dn, Map<String, List<String>> attrs) {
        return new ReconEntry(dn, attrs);
    }

    private DiffResult diff(ReplicationLinkSnapshot link, String targetBase,
                            List<ReconEntry> src, List<ReconEntry> tgt,
                            ReconcileDeleteAction del) {
        return ReconciliationDiffer.diff(link, targetBase, src, tgt, Set.of(), del);
    }

    @Test
    void identicalSubtrees_yieldNoFindings() {
        var link = identityLink();
        var src = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))));
        var tgt = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))));
        DiffResult r = diff(link, null, src, tgt, ReconcileDeleteAction.REVIEW);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void missingEntry_yieldsAdd() {
        var link = identityLink();
        var src = List.of(entry("uid=b,dc=x", Map.of("cn", List.of("Bob"), "sn", List.of("B"))));
        DiffResult r = diff(link, null, src, List.of(), ReconcileDeleteAction.REVIEW);

        assertThat(r.missingCount()).isEqualTo(1);
        var f = r.findings().get(0);
        assertThat(f.type()).isEqualTo(ReconciliationFindingType.MISSING_IN_TARGET);
        assertThat(f.operation()).isEqualTo(ReplicationOperationType.ADD);
        assertThat(f.targetDn()).isEqualTo("uid=b,dc=x");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> attrs = (Map<String, List<String>>) f.payload().get("attributes");
        assertThat(attrs).containsKeys("cn", "sn");
    }

    @Test
    void attributeDrift_yieldsModifyWithBefore() {
        var link = identityLink();
        var src = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("New"))));
        var tgt = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("Old"))));
        DiffResult r = diff(link, null, src, tgt, ReconcileDeleteAction.REVIEW);

        assertThat(r.driftCount()).isEqualTo(1);
        var f = r.findings().get(0);
        assertThat(f.type()).isEqualTo(ReconciliationFindingType.ATTRIBUTE_DRIFT);
        assertThat(f.operation()).isEqualTo(ReplicationOperationType.MODIFY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods = (List<Map<String, Object>>) f.payload().get("modifications");
        assertThat(mods).hasSize(1);
        assertThat(mods.get(0)).containsEntry("type", "REPLACE").containsEntry("name", "cn");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) mods.get(0).get("values");
        assertThat(values).containsExactly("New");
        assertThat(f.payload()).containsKey("before");
    }

    @Test
    void extraInTarget_gatedByDeleteAction() {
        var link = identityLink();
        var src = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))));
        var tgt = List.of(
                entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))),
                entry("uid=z,dc=x", Map.of("cn", List.of("Zed"))));   // no source match

        assertThat(diff(link, "dc=x", src, tgt, ReconcileDeleteAction.IGNORE).findings()).isEmpty();

        DiffResult review = diff(link, "dc=x", src, tgt, ReconcileDeleteAction.REVIEW);
        assertThat(review.extraCount()).isEqualTo(1);
        var f = review.findings().get(0);
        assertThat(f.type()).isEqualTo(ReconciliationFindingType.EXTRA_IN_TARGET);
        assertThat(f.operation()).isEqualTo(ReplicationOperationType.DELETE);
        assertThat(f.targetDn()).isEqualTo("uid=z,dc=x");
        assertThat(f.sourceDn()).isNull();
    }

    @Test
    void baseEntryIsNeverFlaggedAsExtra() {
        var link = identityLink();
        var src = List.of(entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))));
        // Target subtree includes the base entry itself; it must not be a DELETE.
        var tgt = List.of(
                entry("dc=x", Map.of("dc", List.of("x"))),
                entry("uid=a,dc=x", Map.of("cn", List.of("Ann"))));
        DiffResult r = diff(link, "dc=x", src, tgt, ReconcileDeleteAction.AUTO);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void operationalAndPasswordAttrs_notTreatedAsDrift() {
        var link = identityLink();
        var src = List.of(entry("uid=a,dc=x", Map.of(
                "cn", List.of("Ann"),
                "userPassword", List.of("s3cret"),
                "createTimestamp", List.of("20260101000000Z"))));
        var tgt = List.of(entry("uid=a,dc=x", Map.of(
                "cn", List.of("Ann"),
                "createTimestamp", List.of("20990101000000Z"))));   // differs, but excluded
        DiffResult r = diff(link, "dc=x", src, tgt, ReconcileDeleteAction.REVIEW);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void attributeRename_reflectedInExpectedState() {
        var link = link(null, null, List.of(new AttrMappingSnapshot("uid", "sAMAccountName", null)));
        var src = List.of(entry("uid=a,dc=x", Map.of("uid", List.of("a"), "cn", List.of("Ann"))));
        DiffResult r = diff(link, null, src, List.of(), ReconcileDeleteAction.REVIEW);

        var f = r.findings().get(0);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> attrs = (Map<String, List<String>>) f.payload().get("attributes");
        assertThat(attrs).containsKey("sAMAccountName").doesNotContainKey("uid");
    }

    @Test
    void dnBaseSubstitution_mapsTargetDn() {
        var link = link("ou=s,dc=x", "ou=t,dc=y", List.of());
        var src = List.of(entry("uid=a,ou=s,dc=x", Map.of("cn", List.of("Ann"))));
        DiffResult r = diff(link, "ou=t,dc=y", src, List.of(), ReconcileDeleteAction.REVIEW);

        var f = r.findings().get(0);
        assertThat(ReconciliationDiffer.normDn(f.targetDn()))
                .isEqualTo(ReconciliationDiffer.normDn("uid=a,ou=t,dc=y"));
    }

    @Test
    void findingShadowedByUndeliveredEvent_isSuppressed() {
        var link = identityLink();
        var src = List.of(entry("uid=b,dc=x", Map.of("cn", List.of("Bob"))));
        Set<String> undelivered = Set.of(ReconciliationDiffer.normDn("uid=b,dc=x"));
        DiffResult r = ReconciliationDiffer.diff(link, null, src, List.of(), undelivered,
                ReconcileDeleteAction.REVIEW);

        assertThat(r.findings()).isEmpty();
        assertThat(r.suppressedCount()).isEqualTo(1);
        assertThat(r.missingCount()).isZero();
    }
}
