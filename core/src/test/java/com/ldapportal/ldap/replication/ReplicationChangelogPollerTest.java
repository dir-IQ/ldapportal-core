// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.replication.ChangelogPollTxOps.ClaimedPoll;
import com.ldapportal.ldap.replication.reconcile.ReconciliationService;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.ldapportal.service.AuditService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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
    @Mock private ReconciliationService      reconciliationService;
    @Mock private AuditService               auditService;
    @Mock private EntitlementService         entitlementService;
    @InjectMocks private ReplicationChangelogPoller poller;

    private static final UUID LINK = UUID.randomUUID();
    private static final String BIND_DN = "cn=portal,dc=src,dc=com";

    @Test
    void leaseNotAcquired_isNoOp() {
        when(txOps.tryClaim(eq(LINK), any(), any())).thenReturn(Optional.empty());

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(txOps, never()).release(any());
        verify(connectionFactory, never()).withConnectionUnreplicated(any(), any());
    }

    @Test
    void firstRun_seedsCursor_emitsNothing() {
        claim(null);
        stubRead(100L, List.of());

        poller.pollLink(LINK);

        verify(txOps).seed(eq(LINK), eq(100L), any());
        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
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

        ArgumentCaptor<List<PendingReplicationEvent>> cap = captor();
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
        assertThat(cap.getValue()).allSatisfy(e ->
                assertThat(e.enqueueSource().name()).isEqualTo("SOURCE_CHANGELOG"));
        assertThat(cap.getValue().get(0).sourceChangeNumber()).isEqualTo(1L);
        assertThat(cap.getValue().get(1).sourceChangeNumber()).isEqualTo(2L);
        verify(txOps).advance(eq(LINK), eq(0L), eq(2L), eq(2L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void replay_dedupsAlreadyEnqueuedNumbers() {
        claim(0L);
        stubRead(2L, List.of(
                changelogEntry(1, "delete", "uid=a,dc=src,dc=com", null, null),
                changelogEntry(2, "delete", "uid=b,dc=src,dc=com", null, null)));
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of(1L));

        poller.pollLink(LINK);

        ArgumentCaptor<List<PendingReplicationEvent>> cap = captor();
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).singleElement()
                .satisfies(e -> assertThat(e.sourceChangeNumber()).isEqualTo(2L));
        verify(txOps).advance(eq(LINK), eq(0L), eq(2L), eq(2L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void outOfScopeDn_skippedButCursorAdvances() {
        claim(0L, "dc=src,dc=com");
        stubRead(1L, List.of(
                changelogEntry(1, "delete", "uid=x,dc=other,dc=com", null, null)));

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void loopGuard_skipsPortalOwnWrites() {
        claim(0L);
        stubRead(1L, List.of(
                changelogEntry(1, "delete", "uid=loop,dc=src,dc=com", null, BIND_DN)));

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void poisonEntry_deadLettered_andCursorAdvances() {
        // §7A.3: a malformed entry is dead-lettered (recoverable + audited),
        // not silently skipped, and the link still advances.
        claim(0L);
        stubRead(1L, List.of(
                changelogEntry(1, "modify", "uid=bad,dc=src,dc=com", "not valid ldif no colon", null)));
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of());

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(persister).saveDeadLetteredChangelogEvent(
                eq(LINK), any(), eq("uid=bad,dc=src,dc=com"), eq("uid=bad,dc=src,dc=com"),
                anyMap(), eq(1L), any());
        verify(auditService).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_CHANGELOG_ENTRY_DEAD_LETTERED), anyMap());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void nonNumericChangeNumber_isSkipped_doesNotWedgeLink() {
        claim(0L);
        SearchResultEntry bad = new SearchResultEntry("changeNumber=x,cn=changelog",
                new Attribute[]{
                        new Attribute("changeNumber", "not-a-number"),
                        new Attribute("changeType", "delete"),
                        new Attribute("targetDN", "uid=bad,dc=src,dc=com")});
        stubRead(7L, List.of(
                bad,
                changelogEntry(7, "delete", "uid=ok,dc=src,dc=com", null, null)));
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of());

        poller.pollLink(LINK);

        ArgumentCaptor<List<PendingReplicationEvent>> cap = captor();
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).singleElement()
                .satisfies(e -> assertThat(e.sourceChangeNumber()).isEqualTo(7L));
        verify(txOps).advance(eq(LINK), eq(0L), eq(7L), eq(7L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void excludedAdd_isSkipped_cursorAdvances() {
        // §7B: an ADD whose full attributes match the exclude filter is not
        // replicated; the cursor still advances. ADD is evaluated inline (no
        // re-read).
        claim(0L, null, ChangelogHealth.HEALTHY, "(objectClass=computer)");
        stubRead(1L, List.of(
                changelogEntry(1, "add", "cn=ws1,dc=src,dc=com", "objectClass: computer\ncn: ws1\n", null)));

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void nonExcludedAdd_underFilter_isReplicated() {
        claim(0L, null, ChangelogHealth.HEALTHY, "(objectClass=computer)");
        stubRead(1L, List.of(
                changelogEntry(1, "add", "uid=alice,dc=src,dc=com", "objectClass: inetOrgPerson\nuid: alice\n", null)));
        when(eventRepo.findExistingChangelogNumbers(eq(LINK), any())).thenReturn(List.of());

        poller.pollLink(LINK);

        verify(persister).saveAll(any());
        verify(txOps).advance(eq(LINK), eq(0L), eq(1L), eq(1L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void noNewEntries_recordsObservationNotAdvance() {
        claim(5L);
        stubRead(5L, List.of());

        poller.pollLink(LINK);

        verify(persister, never()).saveAll(any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(txOps).observe(eq(LINK), eq(5L), eq(ChangelogHealth.HEALTHY), any());
    }

    @Test
    void cursorReset_haltsWithoutAdvancing_andAudits() {
        // §7A.2: source head below our cursor → changelog reinitialized.
        claim(100L);
        stubRead(50L, List.of());                     // head 50 < cursor 100

        poller.pollLink(LINK);

        verify(txOps).markCursorReset(eq(LINK), eq(50L), any());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(persister, never()).saveAll(any());
        verify(auditService).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_CHANGELOG_CURSOR_RESET), anyMap());
        verify(reconciliationService, never()).trigger(any(), any(), any());
    }

    @Test
    void alreadyCursorReset_isSkipped_noReadNoAuditStorm() {
        // A link already flagged CURSOR_RESET must stay halted silently — no
        // changelog read, no repeated audit/SIEM (the storm fix).
        claim(100L, null, ChangelogHealth.CURSOR_RESET);

        poller.pollLink(LINK);

        verify(connectionFactory, never()).withConnectionUnreplicated(any(), any());
        verify(txOps, never()).markCursorReset(any(), anyLong(), any());
        verify(auditService, never()).recordSystemEventNoActor(any(), any());
        verify(txOps).release(LINK);   // lease still released
    }

    @Test
    void gap_fastForwardsAndTriggersReconcile() {
        // §7A.1: entries trimmed before we read them (cursor+1 < firstChangeNumber).
        claim(10L);
        stubReadWithFirst(2000L, 500L, List.of());    // first=500, cursor=10 → gap
        when(txOps.markGap(eq(LINK), eq(10L), eq(499L), eq(2000L), any())).thenReturn(true);

        poller.pollLink(LINK);

        verify(reconciliationService).trigger(eq(LINK), eq(ReconciliationRunTrigger.MANUAL), any());
        verify(txOps).markGap(eq(LINK), eq(10L), eq(499L), eq(2000L), any());
        verify(auditService).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_CHANGELOG_GAP_DETECTED), anyMap());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(persister, never()).saveAll(any());
    }

    @Test
    void gap_reconcileTriggerFails_doesNotFastForwardOrAudit() {
        // If the gap-recovery reconcile can't be triggered, leave the cursor so
        // the next poll re-detects and retries — never skip the span unrepaired.
        claim(10L);
        stubReadWithFirst(2000L, 500L, List.of());
        when(reconciliationService.trigger(any(), any(), any()))
                .thenThrow(new RuntimeException("reconciliation DB unavailable"));

        poller.pollLink(LINK);

        verify(txOps, never()).markGap(any(), anyLong(), anyLong(), anyLong(), any());
        verify(auditService, never()).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_CHANGELOG_GAP_DETECTED), anyMap());
        verify(txOps, never()).advance(any(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void configError_disablesLink_notTransientRecord() {
        claim(0L);
        when(connectionFactory.withConnectionUnreplicated(eq(source), any()))
                .thenThrow(new RuntimeException("LDAP bind failed: invalid credentials"));

        poller.pollLink(LINK);

        verify(txOps).disableForConfigError(eq(LINK), any(), any());
        verify(txOps, never()).recordError(any(), any(), any());
        verify(txOps).release(LINK);
    }

    @Test
    void transientError_recordsError_notDisable() {
        claim(0L);
        when(connectionFactory.withConnectionUnreplicated(eq(source), any()))
                .thenThrow(new RuntimeException("connection reset by peer"));

        poller.pollLink(LINK);

        verify(txOps).recordError(eq(LINK), any(), any());
        verify(txOps, never()).disableForConfigError(any(), any(), any());
    }

    @Test
    void alreadyDisabledConfigError_isSkipped_noRead() {
        claim(0L, null, ChangelogHealth.DISABLED_CONFIG_ERROR);

        poller.pollLink(LINK);

        verify(connectionFactory, never()).withConnectionUnreplicated(any(), any());
        verify(txOps).release(LINK);
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
        claim(cursor, sourceBaseDn, ChangelogHealth.HEALTHY, null);
    }

    private void claim(Long cursor, String sourceBaseDn, ChangelogHealth health) {
        claim(cursor, sourceBaseDn, health, null);
    }

    private void claim(Long cursor, String sourceBaseDn, ChangelogHealth health, String excludeFilter) {
        when(txOps.tryClaim(eq(LINK), any(), any()))
                .thenReturn(Optional.of(new ClaimedPoll(
                        ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", cursor, health)));
        DirectoryConnection src = new DirectoryConnection();
        src.setId(UUID.randomUUID());
        src.setDisplayName("Source");
        src.setBindDn(BIND_DN);
        src.setBaseDn("dc=src,dc=com");
        ReplicationLinkSnapshot snap = new ReplicationLinkSnapshot(
                LINK, "cl-link", src, new DirectoryConnection(), sourceBaseDn, sourceBaseDn,
                true, false, com.ldapportal.entity.enums.ReplicationCaptureMode.CHANGELOG, excludeFilter, List.of());
        when(readOps.snapshotById(LINK)).thenReturn(Optional.of(snap));
        this.source = src;
    }

    private DirectoryConnection source;

    /** Root DSE with only lastChangeNumber → firstChangeNumber null → no gap check. */
    private void stubRead(long head, List<SearchResultEntry> entries) {
        stubReadEntry(new Entry("", new Attribute("lastChangeNumber", Long.toString(head))), entries);
    }

    /** Root DSE with both first and last → exercises gap detection. */
    private void stubReadWithFirst(long head, long first, List<SearchResultEntry> entries) {
        stubReadEntry(new Entry("",
                new Attribute("lastChangeNumber", Long.toString(head)),
                new Attribute("firstChangeNumber", Long.toString(first))), entries);
    }

    private void stubReadEntry(Entry rootDseEntry, List<SearchResultEntry> entries) {
        FullLDAPInterface iface = mock(FullLDAPInterface.class);
        try {
            when(iface.getRootDSE()).thenReturn(new RootDSE(rootDseEntry));
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
        List<Attribute> attrs = new ArrayList<>();
        attrs.add(new Attribute("changeNumber", Integer.toString(changeNumber)));
        attrs.add(new Attribute("changeType", changeType));
        attrs.add(new Attribute("targetDN", targetDn));
        if (changes != null) attrs.add(new Attribute("changes", changes));
        if (creatorsName != null) attrs.add(new Attribute("creatorsName", creatorsName));
        return new SearchResultEntry("changeNumber=" + changeNumber + ",cn=changelog",
                attrs.toArray(new Attribute[0]));
    }
}
