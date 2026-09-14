// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ranged-retrieval completion for Active Directory's {@code attr;range=lo-hi}
 * chunks. The in-memory server never emits ranges, so the follow-up reads
 * are scripted on a mocked {@link LDAPInterface}.
 */
class RangedAttributeResolverTest {

    private static final String DN = "cn=big,ou=groups,dc=example,dc=com";

    private static SearchResultEntry entry(Attribute... attrs) {
        return new SearchResultEntry(new Entry(DN, attrs));
    }

    @Test
    void entryWithoutRangedAttributes_isReturnedUntouched() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("cn", "big"), new Attribute("member", "uid=a", "uid=b"));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out).isSameAs(in);
        verify(conn, never()).searchForEntry(any(SearchRequest.class));
    }

    @Test
    void followsChunksUntilFinalStarRange_andMergesUnderBaseName() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        // AD's first reply: empty plain `member` beside the first chunk.
        SearchResultEntry in = entry(
                new Attribute("cn", "big"),
                new Attribute("member"),
                new Attribute("member;range=0-1", "uid=a", "uid=b"));
        when(conn.searchForEntry(any(SearchRequest.class)))
                .thenReturn(entry(new Attribute("member;range=2-3", "uid=c", "uid=d")))
                .thenReturn(entry(new Attribute("member;range=4-*", "uid=e")));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out.getAttributeValues("member")).containsExactly("uid=a", "uid=b", "uid=c", "uid=d", "uid=e");
        assertThat(out.getAttributes()).extracting(Attribute::getName).containsExactlyInAnyOrder("cn", "member");
        assertThat(out.getAttributeValue("cn")).isEqualTo("big");

        ArgumentCaptor<SearchRequest> reqs = ArgumentCaptor.forClass(SearchRequest.class);
        verify(conn, times(2)).searchForEntry(reqs.capture());
        List<SearchRequest> sent = reqs.getAllValues();
        assertThat(sent.get(0).getBaseDN()).isEqualTo(DN);
        assertThat(sent.get(0).getScope()).isEqualTo(SearchScope.BASE);
        assertThat(sent.get(0).getAttributes()).containsExactly("member;range=2-*");
        assertThat(sent.get(1).getAttributes()).containsExactly("member;range=4-*");
    }

    @Test
    void finalChunkInInitialEntry_needsNoFollowUp() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("member;range=0-*", "uid=a", "uid=b"));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out.getAttributeValues("member")).containsExactly("uid=a", "uid=b");
        verify(conn, never()).searchForEntry(any(SearchRequest.class));
    }

    @Test
    void serverIgnoringRangeRequest_stopsWithWhatWasCollected() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("member;range=0-1", "uid=a", "uid=b"));
        // Reply carries no member attribute at all → cannot advance.
        when(conn.searchForEntry(any(SearchRequest.class))).thenReturn(entry(new Attribute("cn", "big")));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out.getAttributeValues("member")).containsExactly("uid=a", "uid=b");
        verify(conn, times(1)).searchForEntry(any(SearchRequest.class));
    }

    @Test
    void repeatedChunk_doesNotLoop() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("member;range=0-1", "uid=a", "uid=b"));
        when(conn.searchForEntry(any(SearchRequest.class)))
                .thenReturn(entry(new Attribute("member;range=0-1", "uid=a", "uid=b")));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out.getAttributeValues("member")).containsExactly("uid=a", "uid=b");
        verify(conn, times(1)).searchForEntry(any(SearchRequest.class));
    }

    @Test
    void wholeAttributeInFollowUp_completes() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("uniqueMember;range=0-0", "uid=a"));
        when(conn.searchForEntry(any(SearchRequest.class)))
                .thenReturn(entry(new Attribute("uniqueMember", "uid=b", "uid=c")));

        SearchResultEntry out = RangedAttributeResolver.resolve(conn, in);

        assertThat(out.getAttributeValues("uniqueMember")).containsExactly("uid=a", "uid=b", "uid=c");
        verify(conn, times(1)).searchForEntry(any(SearchRequest.class));
    }

    @Test
    void mappedGroup_seesCompleteMembership() throws LDAPException {
        LDAPInterface conn = mock(LDAPInterface.class);
        SearchResultEntry in = entry(new Attribute("cn", "big"), new Attribute("member;range=0-0", "uid=a"));
        when(conn.searchForEntry(any(SearchRequest.class)))
                .thenReturn(entry(new Attribute("member;range=1-*", "uid=b")));

        var group = LdapEntryMapper.toGroup(RangedAttributeResolver.resolve(conn, in));

        assertThat(group.getMember()).containsExactly("uid=a", "uid=b");
    }
}
