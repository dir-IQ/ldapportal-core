// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.replication.ChangelogTestResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangelogTestServiceTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private LdapConnectionFactory         connectionFactory;
    @InjectMocks private ChangelogTestService service;

    private final UUID DIR = UUID.randomUUID();

    @Test
    void reachable_whenRootDseHasFirstLast_andChangelogReadable() {
        DirectoryConnection dir = stubDir();
        stubConnection(dir, iface(rootDse(1L, 100L),
                searchResult(List.of(entry("changeNumber=100,cn=changelog",
                        new Attribute("changeNumber", "100"))))));

        ChangelogTestResult r = service.test(DIR, "cn=changelog");

        assertThat(r.reachable()).isTrue();
        assertThat(r.currentHead()).isEqualTo(100L);
        assertThat(r.firstChangeNumber()).isEqualTo(1L);
    }

    @Test
    void hardFail_whenRootDseMissingFirstChangeNumber() {
        DirectoryConnection dir = stubDir();
        // Only lastChangeNumber present → gap/reset detection blind → hard fail.
        stubConnection(dir, iface(
                new RootDSE(new Entry("", new Attribute("lastChangeNumber", "100"))),
                searchResult(List.of())));

        ChangelogTestResult r = service.test(DIR, "cn=changelog");

        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("firstChangeNumber");
        assertThat(r.firstChangeNumber()).isNull();
    }

    @Test
    void notReachable_whenEntriesMissingChangeNumber() {
        DirectoryConnection dir = stubDir();
        stubConnection(dir, iface(rootDse(1L, 100L),
                searchResult(List.of(entry("cn=weird,cn=changelog",
                        new Attribute("objectClass", "top"))))));   // no changeNumber

        ChangelogTestResult r = service.test(DIR, "cn=changelog");

        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("changeNumber");
    }

    @Test
    void notReachable_whenConnectionOrBaseDnFails() {
        DirectoryConnection dir = stubDir();
        when(connectionFactory.withConnectionUnreplicated(eq(dir), any()))
                .thenThrow(new LdapOperationException("LDAP operation failed: no such object"));

        ChangelogTestResult r = service.test(DIR, "cn=missing");

        assertThat(r.reachable()).isFalse();
        assertThat(r.message()).contains("Changelog test failed");
    }

    @Test
    void blankBaseDn_defaultsToCnChangelog() {
        DirectoryConnection dir = stubDir();
        stubConnection(dir, iface(rootDse(1L, 5L), searchResult(List.of())));

        ChangelogTestResult r = service.test(DIR, "  ");

        assertThat(r.reachable()).isTrue();
        assertThat(r.message()).contains("cn=changelog");
    }

    @Test
    void unknownDirectory_throwsNotFound() {
        when(dirRepo.findById(DIR)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.test(DIR, "cn=changelog"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private DirectoryConnection stubDir() {
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(DIR);
        dir.setDisplayName("Source");
        when(dirRepo.findById(DIR)).thenReturn(Optional.of(dir));
        return dir;
    }

    private void stubConnection(DirectoryConnection dir, FullLDAPInterface iface) {
        when(connectionFactory.withConnectionUnreplicated(eq(dir), any())).thenAnswer(inv -> {
            LdapConnectionFactory.LdapOperation<?> op = inv.getArgument(1);
            return op.execute(iface);
        });
    }

    private static FullLDAPInterface iface(RootDSE dse, SearchResult result) {
        FullLDAPInterface iface = mock(FullLDAPInterface.class);
        try {
            when(iface.getRootDSE()).thenReturn(dse);
            when(iface.search(any(SearchRequest.class))).thenReturn(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return iface;
    }

    private static RootDSE rootDse(long first, long last) {
        return new RootDSE(new Entry("",
                new Attribute("firstChangeNumber", Long.toString(first)),
                new Attribute("lastChangeNumber", Long.toString(last))));
    }

    private static SearchResult searchResult(List<SearchResultEntry> entries) {
        return new SearchResult(1, ResultCode.SUCCESS, null, null, null,
                entries, null, entries.size(), 0, null);
    }

    private static SearchResultEntry entry(String dn, Attribute... attrs) {
        return new SearchResultEntry(dn, attrs);
    }
}
