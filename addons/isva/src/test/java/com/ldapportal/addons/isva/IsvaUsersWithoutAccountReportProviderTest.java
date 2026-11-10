// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IsvaUsersWithoutAccountReportProvider}: lists the users the enricher
 * flags as having no IVIA account, within the requested scope.
 */
class IsvaUsersWithoutAccountReportProviderTest {

    private static final String PEOPLE = "ou=people,dc=example,dc=com";

    private final LdapUserService userService = mock(LdapUserService.class);
    private final IsvaUserReadEnricher enricher = mock(IsvaUserReadEnricher.class);
    private final IsvaUsersWithoutAccountReportProvider provider =
            new IsvaUsersWithoutAccountReportProvider(userService, enricher);
    private final DirectoryConnection dir = new DirectoryConnection();

    private static LdapUser user(String uid, String hasAccount) {
        Map<String, List<String>> attrs = new java.util.HashMap<>(Map.of(
                "uid", List.of(uid), "cn", List.of(uid.toUpperCase()), "mail", List.of(uid + "@example.com")));
        if (hasAccount != null) attrs.put(IsvaUserReadEnricher.HAS_ACCOUNT, List.of(hasAccount));
        return new LdapUser("uid=" + uid + "," + PEOPLE, attrs);
    }

    @Test
    void reportId_isStable_andDistinctFromTheOrphanedAccountsReport() {
        assertThat(provider.reportId()).isEqualTo("USERS_WITHOUT_IVIA_ACCOUNT")
                .isNotEqualTo(IsvaOrphanedAccountsReportProvider.REPORT_ID);
    }

    @Test
    void appliesTo_followsWhetherTheEnricherCanClassifyUsers() {
        when(enricher.flagsAccounts(dir)).thenReturn(true);
        assertThat(provider.appliesTo(dir)).isTrue();

        when(enricher.flagsAccounts(dir)).thenReturn(false);
        assertThat(provider.appliesTo(dir)).isFalse();
    }

    @Test
    void run_listsOnlyUsersFlaggedWithoutAnAccount_inTheRequestedScope() {
        when(enricher.flagsAccounts(dir)).thenReturn(true);
        when(userService.searchUsers(eq(dir), anyString(), eq(PEOPLE),
                eq(IsvaUsersWithoutAccountReportProvider.MAX_USERS), any(String[].class)))
                .thenReturn(List.of(user("has", "true"), user("none", "false"), user("unknown", null)));

        ReportData data = provider.run(dir, Map.of(), PEOPLE);

        assertThat(data.columns()).containsExactly("dn", "uid", "cn", "mail");
        // An unclassified user (no flag) isn't listed.
        assertThat(data.rows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("dn", "uid=none," + PEOPLE)
                    .containsEntry("uid", "none").containsEntry("cn", "NONE")
                    .containsEntry("mail", "none@example.com");
        });
    }

    @Test
    void run_onADirectoryWithoutIvia_isRefused() {
        when(enricher.flagsAccounts(dir)).thenReturn(false);

        assertThatThrownBy(() -> provider.run(dir, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userService);
    }
}
