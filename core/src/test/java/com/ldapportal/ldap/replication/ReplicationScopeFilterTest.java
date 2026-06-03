// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationCaptureMode;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationScopeFilterTest {

    @Test
    void hasExcludeFilter_reflectsPresence() {
        assertThat(ReplicationScopeFilter.hasExcludeFilter(link(null))).isFalse();
        assertThat(ReplicationScopeFilter.hasExcludeFilter(link("  "))).isFalse();
        assertThat(ReplicationScopeFilter.hasExcludeFilter(link("(objectClass=computer)"))).isTrue();
    }

    @Test
    void noFilter_neverExcludes() {
        assertThat(ReplicationScopeFilter.isExcluded(link(null), "uid=a,dc=x",
                Map.of("objectClass", List.of("computer")))).isFalse();
    }

    @Test
    void equalityMatch_excludes_nonMatchDoesNot() {
        var f = link("(objectClass=computer)");
        assertThat(ReplicationScopeFilter.isExcluded(f, "cn=ws1,dc=x",
                Map.of("objectClass", List.of("top", "computer")))).isTrue();
        assertThat(ReplicationScopeFilter.isExcluded(f, "uid=a,dc=x",
                Map.of("objectClass", List.of("inetOrgPerson")))).isFalse();
    }

    @Test
    void orFilter_excludesEitherClause() {
        var f = link("(|(objectClass=computer)(employeeType=contractor))");
        assertThat(ReplicationScopeFilter.isExcluded(f, "uid=c,dc=x",
                Map.of("objectClass", List.of("inetOrgPerson"),
                       "employeeType", List.of("contractor")))).isTrue();
        assertThat(ReplicationScopeFilter.isExcluded(f, "uid=p,dc=x",
                Map.of("objectClass", List.of("inetOrgPerson"),
                       "employeeType", List.of("permanent")))).isFalse();
    }

    @Test
    void attributeAbsent_isNotExcluded() {
        // Filter references an attribute the entry doesn't carry → no match.
        var f = link("(employeeType=contractor)");
        assertThat(ReplicationScopeFilter.isExcluded(f, "uid=a,dc=x",
                Map.of("objectClass", List.of("inetOrgPerson")))).isFalse();
    }

    @Test
    void substringMatch_excludes() {
        var f = link("(cn=*svc*)");
        assertThat(ReplicationScopeFilter.isExcluded(f, "cn=mysvc01,dc=x",
                Map.of("cn", List.of("mysvc01")))).isTrue();
        assertThat(ReplicationScopeFilter.isExcluded(f, "cn=alice,dc=x",
                Map.of("cn", List.of("alice")))).isFalse();
    }

    @Test
    void offlineUnevaluableExtensibleRule_failsOpen_notExcluded() {
        // Server-side-only extensible matching (here a :dn: rule the offline
        // matcher can't evaluate) must NOT throw and must NOT silently drop the
        // entry — it fails open to "not excluded" (§7B.6). Reconciliation stays
        // the backstop.
        var f = link("(ou:dn:=Service Accounts)");
        Entry inOu = new Entry("cn=svc,ou=Service Accounts,dc=x",
                new Attribute("objectClass", "inetOrgPerson"));
        assertThat(ReplicationScopeFilter.isExcluded(f, inOu)).isFalse();
    }

    private static ReplicationLinkSnapshot link(String excludeFilter) {
        return new ReplicationLinkSnapshot(
                UUID.randomUUID(), "L", null, null, null, null, true, false,
                ReplicationCaptureMode.CHANGELOG, excludeFilter, List.of());
    }
}
