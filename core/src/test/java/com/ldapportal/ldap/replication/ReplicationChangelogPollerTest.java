// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.replication.ChangelogPollTxOps.ClaimedPoll;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplicationChangelogPollerTest {

    @Mock private ReplicationLinkRepository  linkRepo;
    @Mock private ReplicationEventRepository eventRepo;
    @Mock private ReplicationReadOps         readOps;
    @Mock private ReplicationEventPersister  persister;
    @Mock private LdapConnectionFactory      connectionFactory;
    @Mock private ChangelogPollTxOps         txOps;
    @Mock private EntitlementService         entitlementService;
    @InjectMocks private ReplicationChangelogPoller poller;

    private static final UUID LINK = UUID.randomUUID();
    private static final String BIND_DN = "cn=portal,dc=src,dc=com";

    @Test
    void leaseNotAcquired_isNoOp() {
        when(txOps.tryClaim(eq(LINK), any(), any())).thenReturn(Optional.empty());

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any());
        // Never claimed → nothing to release; no read attempted.
        verify(txOps, never()).release(any());
        verify(connectionFactory, never()).withConnectionUnreplicated(any(), any());
    }

    @Test
    void firstRun_seedsCursor_emitsNothing() {
        claim(null);                                  // cursor null → first run
        stubRead(100L, List.of());                    // head present, no entries read

        poller.pollLink(LINK);

        verify(txOps).seed(eq(LINK), eq(100L), any());
        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsEvents_andAdvancesCursorToMax() {
        claim(0L);
        stubRead(2L, List.of(
                changelogEntry(1, "modify", "uid=bob,dc=src,dc=com", "replace: mail\nmail: a@x.com\n-", null),
                changelogEntry(2, "delete", "uid=gone,dc=src,dc=com", null, null)));
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of());

        poller.pollLink(LINK);

        ArgumentCaptor<List<PendingReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
        assertThat(cap.getValue()).allSatisfy(e ->
                assertThat(e.enqueueSource().name()).isEqualTo("SOURCE_CHANGELOG"));
        assertThat(cap.getValue().get(0).sourceChangeNumber()).isEqualTo(1L);
        assertThat(cap.getValue().get(1).sourceChangeNumber()).isEqualTo(2L);
        verify(txOps).advance(eq(LINK), eq(0L), eq(2L), eq(2L), any());
    }

    @Test
    void replay_dedupsAlreadyEnqueuedNumbers() {
        claim(0L);
        stubRead(2L, List.of(
                changelogEntry(1, "delete", "uid=a,dc=src,dc=com", null, null),
                changelogEntry(2, "delete", "uid=b,dc=src,dc=com", null, null)));
        // changeNumber 1 was already enqueued before a crash; only 2 is fresh.
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of(1L));

        poller.pollLink(LINK);

        ArgumentCaptor<List<PendingReplicationEvent>> cap = captor();
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).singleElement()
                .satisfies(e -> assertThat(e.sourceChangeNumber()).isEqualTo(2L));
        // Cursor still advances past both — the replayed one isn't lost.
        verify(txOps).advance(eq(LINK), eq(0L), eq(2L), eq(2L), any());
    }

    @Test
    void outOfScopeDn_skippedButCursorAdvances() {
        claim(0L, "dc=src,dc=com");                   // link scoped to dc=src,dc=com
        stubRead(1L, List.of(
                changelogEntry(1, "delete", "uid=x,dc=other,dc=com", null, null)));  // out of scope

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), any());
    }

    @Test
    void loopGuard_skipsPortalOwnWrites() {
        claim(0L);
        // creatorsName == the source bind DN → the portal's own delivery; skip.
        stubRead(1L, List.of(
                changelogEntry(1, "delete", "uid=loop,dc=src,dc=com", null, BIND_DN)));

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), any());
    }

    @Test
    void poisonEntry_skippedPast_notWedged() {
        claim(0L);
        stubRead(1L, List.of(
                changelogEntry(1, "modify", "uid=bad,dc=src,dc=com", "not valid ldif no colon", null)));

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), any());   // advanced past the poison
    }

    @Test
    void noNewEntries_recordsObservationNotAdvance() {
        claim(5L);
        stubRead(5L, List.of());                      // head == cursor, nothing new

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any());
        verify(txOps).observe(eq(LINK), eq(5L), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<PendingReplicationEvent>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private void claim(Long cursor) {
        claim(cursor, null);
    }

    private void claim(Long cursor, String sourceBaseDn) {
        when(txOps.tryClaim(eq(LINK), any(), any()))
                .thenReturn(Optional.of(new ClaimedPoll(ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", cursor)));
        DirectoryConnection source = new DirectoryConnection();
        source.setId(UUID.randomUUID());
        source.setDisplayName("Source");
        source.setBindDn(BIND_DN);
        source.setBaseDn("dc=src,dc=com");
        ReplicationLinkSnapshot snap = new ReplicationLinkSnapshot(
                LINK, "cl-link", source, new DirectoryConnection(), sourceBaseDn, sourceBaseDn,
                true, false, com.ldapportal.entity.enums.ReplicationCaptureMode.CHANGELOG, null, List.of());
        when(readOps.snapshotById(LINK)).thenReturn(Optional.of(snap));
        this.source = source;
    }

    private DirectoryConnection source;

    private void stubRead(long head, List<SearchResultEntry> entries) {
        FullLDAPInterface iface = mock(FullLDAPInterface.class);
        try {
            when(iface.getRootDSE()).thenReturn(
                    new RootDSE(new Entry("", new Attribute("lastChangeNumber", Long.toString(head)))));
            when(iface.search(any(SearchRequest.class))).thenReturn(searchResult(entries));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(connectionFactory.withConnectionUnreplicated(eq(source), any())).thenAnswer(inv -> {
            LdapConnectionFactory.LdapOperation<?> op = inv.getArgument(1);
            return op.execute(iface);
        });
    }

    private static SearchResult searchResult(List<SearchResultEntry> entries) {
        return new SearchResult(1, ResultCode.SUCCESS, null, null, null,
                entries, null, entries.size(), 0, null);
    }

    private static SearchResultEntry changelogEntry(int changeNumber, String changeType, String targetDn,
                                                    String changes, String creatorsName) {
        java.util.List<Attribute> attrs = new java.util.ArrayList<>();
        attrs.add(new Attribute("changeNumber", Integer.toString(changeNumber)));
        attrs.add(new Attribute("changeType", changeType));
        attrs.add(new Attribute("targetDN", targetDn));
        if (changes != null) attrs.add(new Attribute("changes", changes));
        if (creatorsName != null) attrs.add(new Attribute("creatorsName", creatorsName));
        return new SearchResultEntry("changeNumber=" + changeNumber + ",cn=changelog",
                attrs.toArray(new Attribute[0]));
    }
}
