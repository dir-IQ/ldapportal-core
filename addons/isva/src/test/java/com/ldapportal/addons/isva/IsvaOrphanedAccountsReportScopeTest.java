// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The orphaned-accounts report's admin-view scope: an orphan is kept when its
 * {@code secDN} is the scope DN or lies under it, compared as DNs.
 */
class IsvaOrphanedAccountsReportScopeTest {

    private static final String SCOPE = "ou=people,dc=example,dc=com";

    private final IsvaOrphanedSecUsers scan = mock(IsvaOrphanedSecUsers.class);
    private final IsvaOrphanedAccountsReportProvider provider = new IsvaOrphanedAccountsReportProvider(scan);
    private final DirectoryConnection dir = new DirectoryConnection();

    @BeforeEach
    void setUp() {
        dir.setDisplayName("test-ldap");
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        when(scan.activeLinkedConfigOrNull(dir)).thenReturn(cfg);
        when(scan.find(dir, cfg)).thenReturn(List.of(
                orphan("a", "uid=a, ou=people, dc=example, dc=com"),   // spaced: in scope
                orphan("b", "UID=b,OU=People,DC=Example,DC=Com"),       // other case: in scope
                orphan("c", "uid=c,ou=other,dc=example,dc=com"),        // another OU: out
                orphan("d", "uid=d,ou=old-people,dc=example,dc=com"),   // name only ends alike: out
                orphan("e", null)));                                    // no secDN: always kept
    }

    private static IsvaOrphanedSecUsers.Orphan orphan(String login, String secDn) {
        return new IsvaOrphanedSecUsers.Orphan("secUUID=" + login + ",secAuthority=Default",
                login, secDn, "true", null, "Demographic entry not found");
    }

    private static List<String> logins(ReportData data) {
        return data.rows().stream().map(r -> r.get("secLogin")).toList();
    }

    @Test
    void scoped_keepsOrphansUnderTheScope_howeverTheirSecDnIsWritten() {
        assertThat(logins(provider.run(dir, Map.of(), SCOPE))).containsExactly("a", "b", "e");
    }

    @Test
    void scoped_aScopeWrittenWithSpacesAndOtherCase_matchesTheSame() {
        assertThat(logins(provider.run(dir, Map.of(), "OU=People, DC=example,DC=COM")))
                .containsExactly("a", "b", "e");
    }

    @Test
    void unscoped_reportsEveryOrphan() {
        assertThat(logins(provider.run(dir, Map.of(), null))).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void isWithin_includesTheScopeItself_andFallsBackOnUnparseableDns() {
        assertThat(IsvaOrphanedAccountsReportProvider.isWithin(SCOPE, SCOPE)).isTrue();
        assertThat(IsvaOrphanedAccountsReportProvider.isWithin("uid=x,ou=people,dc=example,dc=com=", SCOPE))
                .isFalse();
        assertThat(IsvaOrphanedAccountsReportProvider.isWithin("not a dn,ou=people,dc=example,dc=com", SCOPE))
                .isTrue();
    }
}
