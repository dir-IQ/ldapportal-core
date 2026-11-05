// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The bulk existence check when a search can't be trusted to find what's there. */
@ExtendWith(MockitoExtension.class)
class RecomputeEngineExistingOnTargetTest {

    private static final String PARENT = "ou=Users,dc=dst";

    @Mock private LdapConnectionFactory connectionFactory;
    @Mock private FullLDAPInterface ldap;
    @InjectMocks private RecomputeEngine engine;

    private final DirectoryConnection target = new DirectoryConnection();

    @BeforeEach
    void setUp() {
        when(connectionFactory.withConnectionUnreplicated(any(), any())).thenAnswer(inv ->
                ((LdapConnectionFactory.LdapOperation<?>) inv.getArgument(1)).execute(ldap));
    }

    private static SearchResult found(String... dns) {
        List<SearchResultEntry> entries = java.util.Arrays.stream(dns)
                .map(dn -> new SearchResultEntry(dn, new com.unboundid.ldap.sdk.Attribute[0], new Control[0]))
                .toList();
        return new SearchResult(1, ResultCode.SUCCESS, null, null, null, entries, List.of(), entries.size(), 0,
                null);
    }

    private static SearchResultEntry entry(String dn) {
        return new SearchResultEntry(dn, new com.unboundid.ldap.sdk.Attribute[0], new Control[0]);
    }

    @Test
    void aSearchThatFindsNothing_whileASampledEntryExists_fallsBackToReadingEach() throws Exception {
        // The bind may read entries but not search their naming attribute: the
        // search comes back empty without an error.
        when(ldap.search(any(SearchRequest.class))).thenReturn(found());
        when(ldap.getEntry(anyString(), any(String[].class))).thenAnswer(inv -> {
            String dn = inv.getArgument(0);
            return dn.startsWith("uid=b,") || dn.startsWith("uid=c,") ? entry(dn) : null;
        });

        assertThat(engine.existingOnTarget(target, new java.util.LinkedHashSet<>(List.of(
                "uid=a," + PARENT, "uid=b," + PARENT, "uid=c," + PARENT))))
                .containsExactlyInAnyOrder("uid=b," + PARENT, "uid=c," + PARENT);
    }

    @Test
    void aSearchThatFindsNothing_whenNothingIsThere_isTrusted() throws Exception {
        when(ldap.search(any(SearchRequest.class))).thenReturn(found());
        when(ldap.getEntry(anyString(), any(String[].class))).thenReturn(null);
        Set<String> dns = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 10; i++) {
            dns.add("uid=u" + i + "," + PARENT);
        }

        assertThat(engine.existingOnTarget(target, dns)).isEmpty();
        // Only the sample (first, middle, last) was read, not all ten.
        verify(ldap, org.mockito.Mockito.times(3)).getEntry(anyString(), any(String[].class));
    }

    @Test
    void aSearchThatFindsSome_isTrusted_withoutReads() throws Exception {
        when(ldap.search(any(SearchRequest.class))).thenReturn(found("UID=B, ou=Users,dc=dst"));
        lenient().when(ldap.getEntry(anyString(), any(String[].class))).thenReturn(null);

        assertThat(engine.existingOnTarget(target, new java.util.LinkedHashSet<>(List.of(
                "uid=a," + PARENT, "uid=b," + PARENT)))).containsExactly("uid=b," + PARENT);
        verify(ldap, never()).getEntry(anyString(), any(String[].class));
    }
}
