// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Canonicalisation contract for the checksum-diff digest (R-PP1). */
class ReconciliationDigestTest {

    @Test
    void equalManagedState_hashesEqual_regardlessOfCaseAndValueOrder() {
        String a = ReconciliationDigest.digest(Map.of(
                "cn", List.of("Ann"),
                "memberOf", List.of("g1", "g2")));
        String b = ReconciliationDigest.digest(Map.of(
                "CN", List.of("Ann"),                 // name case differs
                "memberof", List.of("g2", "g1")));    // value order differs
        assertThat(a).isEqualTo(b);
    }

    @Test
    void excludedAttributeChange_doesNotAffectDigest() {
        String a = ReconciliationDigest.digest(Map.of(
                "cn", List.of("Ann"),
                "modifyTimestamp", List.of("20260101000000Z"),
                "userPassword", List.of("old")));
        String b = ReconciliationDigest.digest(Map.of(
                "cn", List.of("Ann"),
                "modifyTimestamp", List.of("20990101000000Z"),
                "userPassword", List.of("new")));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void valueCaseDifference_hashesEqual() {
        // caseIgnore default: values differing only in case are not drift.
        String a = ReconciliationDigest.digest(Map.of("cn", List.of("Ann"), "mail", List.of("A@X.COM")));
        String b = ReconciliationDigest.digest(Map.of("cn", List.of("ann"), "mail", List.of("a@x.com")));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void managedValueChange_changesDigest() {
        String a = ReconciliationDigest.digest(Map.of("cn", List.of("Ann")));
        String b = ReconciliationDigest.digest(Map.of("cn", List.of("Bob")));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void addingManagedValue_changesDigest() {
        String a = ReconciliationDigest.digest(Map.of("memberOf", List.of("g1")));
        String b = ReconciliationDigest.digest(Map.of("memberOf", List.of("g1", "g2")));
        assertThat(a).isNotEqualTo(b);
    }
}
