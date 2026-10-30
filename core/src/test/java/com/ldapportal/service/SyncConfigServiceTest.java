// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncRecheckResult;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncLoggingLevel;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.ldap.sync.SyncContentVerifier;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Transform-rule validation + normalization on the sync-set config path. The
 * engine matches first-wins on a non-blank sourceAttr and understands only the
 * single {@code ${value}} template token, so the service rejects config that
 * couldn't work and canonicalizes what it stores.
 */
@ExtendWith(MockitoExtension.class)
class SyncConfigServiceTest {

    @Mock private SyncLinkRepository linkRepo;
    @Mock private SyncSetRepository setRepo;
    @Mock private MembershipRepository membershipRepo;
    @Mock private DirectoryConnectionRepository directoryRepo;
    @Mock private com.ldapportal.ldap.sync.SyncVerifyProgressTracker verifyProgress;
    @Mock private MembershipReconciler reconciler;
    @Mock private SyncContentVerifier verifier;
    @Mock private RecomputeEnqueuer enqueuer;
    @Mock private com.ldapportal.ldap.sync.SyncPreviewService previewService;
    @Mock private com.ldapportal.ldap.sync.SyncReconcileHistory reconcileHistory;
    @Mock private com.ldapportal.ldap.sync.RecomputeEngine engine;
    @Mock private AuditService auditService;
    @Mock private com.ldapportal.repository.SyncReconcileRunRepository reconcileRunRepo;

    private SyncConfigService service;

    @BeforeEach
    void setUp() {
        service = new SyncConfigService(linkRepo, setRepo, membershipRepo, directoryRepo,
                reconciler, verifier, enqueuer, previewService, reconcileHistory, engine, auditService,
                reconcileRunRepo, verifyProgress);
    }

    private SyncSetRequest req(List<SyncTransformRule> rules) {
        return new SyncSetRequest(UUID.randomUUID(), "people", "ou=people,dc=src,dc=com", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, rules, null, null, true, null);
    }

    @Test
    void rejectsBlankSourceAttr() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(new SyncTransformRule("  ", "x", null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceAttr is required");
    }

    @Test
    void rejectsDuplicateSourceAttr() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(
                new SyncTransformRule("uid", "sAMAccountName", null),
                new SyncTransformRule("UID", "x", null)))))  // same source, different case
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsUnknownTemplateToken() {
        when(linkRepo.existsById(any())).thenReturn(true);
        assertThatThrownBy(() -> service.createSet(req(List.of(
                new SyncTransformRule("cn", "cn", "${other}")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only ${value}");
    }

    @Test
    void normalizesValidRules() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createSet(req(List.of(
                new SyncTransformRule(" uid ", " sAMAccountName ", null),  // trimmed
                new SyncTransformRule("cn", "  ", "Mr ${value}"))));        // blank target → null

        ArgumentCaptor<SyncSet> cap = ArgumentCaptor.forClass(SyncSet.class);
        verify(setRepo).save(cap.capture());
        List<SyncTransformRule> stored = cap.getValue().getTransformRules();

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getSourceAttr()).isEqualTo("uid");
        assertThat(stored.get(0).getTargetAttr()).isEqualTo("sAMAccountName");
        assertThat(stored.get(1).getTargetAttr()).isNull();
        assertThat(stored.get(1).getValueTemplate()).isEqualTo("Mr ${value}");
    }

    @Test
    void loggingLevel_isSetWhenGiven_summaryOnCreate_andLeftAloneWhenNull() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest base = req(List.of());
        SyncSetRequest detailed = new SyncSetRequest(base.linkId(), base.name(), base.objectScopeBaseDn(), null,
                null, null, null, null, null, base.deletePolicy(), null, null, null, true, SyncLoggingLevel.DETAILED);

        assertThat(service.createSet(detailed).loggingLevel()).isEqualTo(SyncLoggingLevel.DETAILED);
        assertThat(service.createSet(base).loggingLevel()).isEqualTo(SyncLoggingLevel.SUMMARY);

        UUID setId = UUID.randomUUID();
        SyncSet existing = new SyncSet();
        existing.setLoggingLevel(SyncLoggingLevel.DETAILED);
        when(setRepo.findById(setId)).thenReturn(Optional.of(existing));
        assertThat(service.updateSet(setId, base).loggingLevel()).isEqualTo(SyncLoggingLevel.DETAILED); // null: unchanged
    }

    @Test
    void applicabilityFilter_laidOutOverSeveralLines_isStoredAsOneLine() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest base = req(List.of());
        SyncSetRequest multiline = new SyncSetRequest(base.linkId(), base.name(), base.objectScopeBaseDn(), null,
                null, null, "(&(objectClass=secGroup)\n  (!(|(cn=iv-admin)\r\n      (cn=John Smith))))\n", null, null,
                base.deletePolicy(), null, null, null, true, null);

        assertThat(service.createSet(multiline).applicabilityFilter())
                .isEqualTo("(&(objectClass=secGroup)(!(|(cn=iv-admin)(cn=John Smith))))");
    }

    @Test
    void sourceBaseDn_isRequired() {
        when(linkRepo.existsById(any())).thenReturn(true);
        SyncSetRequest base = req(List.of());
        SyncSetRequest blank = new SyncSetRequest(base.linkId(), base.name(), "  ", null, null, null, null, null,
                null, base.deletePolicy(), null, null, null, true, null);

        assertThatThrownBy(() -> service.createSet(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Source base DN is required.");
        verify(setRepo, never()).save(any());
    }

    @Test
    void writeRunEntriesCsv_writesOneQuotedRowPerEntry() {
        UUID setId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<com.ldapportal.ldap.sync.SyncRunEntryStore.Entry> sink = inv.getArgument(2);
            sink.accept(new com.ldapportal.ldap.sync.SyncRunEntryStore.Entry(0, "ADD", "uid=a,dc=dst", null));
            sink.accept(new com.ldapportal.ldap.sync.SyncRunEntryStore.Entry(1, "ERROR", "uid=b,dc=dst",
                    "apply failed: \"quoted\""));
            return null;
        }).when(reconcileHistory).entries(eq(setId), eq(runId), any());
        java.io.StringWriter out = new java.io.StringWriter();

        service.writeRunEntriesCsv(setId, runId, out);

        assertThat(out.toString()).isEqualTo("\"outcome\",\"dn\",\"reason\"\r\n"
                + "\"ADD\",\"uid=a,dc=dst\",\"\"\r\n"
                + "\"ERROR\",\"uid=b,dc=dst\",\"apply failed: \"\"quoted\"\"\"\r\n");
    }

    @Test
    void emptyRuleListStoresNull() {
        when(linkRepo.existsById(any())).thenReturn(true);
        when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createSet(req(List.of()));

        ArgumentCaptor<SyncSet> cap = ArgumentCaptor.forClass(SyncSet.class);
        verify(setRepo).save(cap.capture());
        assertThat(cap.getValue().getTransformRules()).isNull();
    }

    @Test
    void verifyContents_persistsDriftSnapshot_whenScanComplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                10, 9, 7, 2, 1, 0,
                List.of(), List.of(), List.of(),
                true, true, null)); // complete scan: missing=2, orphan=1, mismatch=0

        SyncVerifyResult out = service.verifyContents(setId);

        assertThat(out.missingOnTarget()).isEqualTo(2);
        // The drift counts are cached on the set so the dashboard can read them cheaply.
        verify(setRepo).recordVerifyResult(eq(setId), any(), eq(2), eq(1), eq(0));
    }

    @Test
    void verifyContents_skipsPersist_whenScanIncomplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                false, true, null)); // partial: source enumeration failed

        service.verifyContents(setId);

        // A partial scan must not overwrite a previously good snapshot with low counts.
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcileNow_refreshesDriftSnapshot_whenScanComplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(42);
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                10, 9, 7, 2, 1, 0,
                List.of(), List.of(), List.of(),
                true, true, null)); // complete scan: missing=2, orphan=1, mismatch=0

        int enumerated = service.reconcileNow(setId, "alice");

        assertThat(enumerated).isEqualTo(42);
        // A manual reconcile must refresh the cached drift, else the dashboard stays stale.
        verify(setRepo).recordVerifyResult(eq(setId), any(), eq(2), eq(1), eq(0));
        // ...and record it on the run it follows.
        verify(reconcileHistory).recordDrift(setId, 2, 1, 0);
    }

    @Test
    void reconcileNow_skipsPersist_whenScanIncomplete() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(1);
        when(verifier.verify(setId)).thenReturn(new SyncVerifyResult(
                0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(),
                false, true, null)); // partial: source enumeration failed

        service.reconcileNow(setId, "alice");

        // A partial scan must not overwrite a previously good snapshot with low counts.
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcileNow_returnsCount_whenSnapshotRefreshThrows() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(reconciler.reconcile(setId, SyncReconcileTrigger.MANUAL, "alice")).thenReturn(5);
        when(verifier.verify(setId)).thenThrow(new RuntimeException("ldap read blew up"));

        // The reconcile already applied; a verify failure during the refresh must not
        // surface as a failed reconcile.
        int enumerated = service.reconcileNow(setId, "alice");

        assertThat(enumerated).isEqualTo(5);
        verify(setRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void createSet_onAMultiRootDirectory_requiresExplicitBases() {
        UUID linkId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(srcId);
        link.setTargetDirId(dstId);
        com.ldapportal.entity.DirectoryConnection multi = new com.ldapportal.entity.DirectoryConnection();
        multi.setBaseDn("dc=example,dc=com");
        multi.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("o=partners")));
        com.ldapportal.entity.DirectoryConnection single = new com.ldapportal.entity.DirectoryConnection();
        single.setBaseDn("dc=dst,dc=com");
        when(linkRepo.existsById(linkId)).thenReturn(true);
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(directoryRepo.findById(srcId)).thenReturn(Optional.of(multi));
        org.mockito.Mockito.lenient().when(directoryRepo.findById(dstId)).thenReturn(Optional.of(single));

        SyncSetRequest outside = new SyncSetRequest(linkId, "people", "ou=people,dc=other", null, null,
                "ou=Users,dc=dst,dc=com", null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);
        assertThatThrownBy(() -> service.createSet(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'ou=people,dc=other' isn't under any of the source directory's base DNs");

        // Under either of the source's roots is fine.
        org.mockito.Mockito.lenient().when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SyncSetRequest secondRoot = new SyncSetRequest(linkId, "partners", "ou=people,o=partners", null, null,
                "ou=Users,dc=dst,dc=com", null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);
        assertThat(service.createSet(secondRoot).objectScopeBaseDn()).isEqualTo("ou=people,o=partners");
    }

    /**
     * A link from a source with roots c=us and dc=elsewhere to a target with
     * roots c=us and o=partners.
     */
    private UUID linkToMultiRootTarget() {
        UUID linkId = UUID.randomUUID();
        UUID srcId = UUID.randomUUID();
        UUID dstId = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(srcId);
        link.setTargetDirId(dstId);
        com.ldapportal.entity.DirectoryConnection source = new com.ldapportal.entity.DirectoryConnection();
        source.setBaseDn("c=us");
        source.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("dc=elsewhere")));
        com.ldapportal.entity.DirectoryConnection multi = new com.ldapportal.entity.DirectoryConnection();
        multi.setBaseDn("c=us");
        multi.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("o=partners")));
        when(linkRepo.existsById(linkId)).thenReturn(true);
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        org.mockito.Mockito.lenient().when(directoryRepo.findById(srcId)).thenReturn(Optional.of(source));
        when(directoryRepo.findById(dstId)).thenReturn(Optional.of(multi));
        org.mockito.Mockito.lenient().when(setRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return linkId;
    }

    @Test
    void blankTargetBase_onAMultiRootTarget_isAccepted_whenTheSourceScopeIsUnderOneOfItsRoots() {
        UUID linkId = linkToMultiRootTarget();
        SyncSetRequest sameDns = new SyncSetRequest(linkId, "groups", "o=groups,c=us", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);

        assertThat(service.createSet(sameDns).targetBaseDn()).isNull(); // entries keep their source DNs
    }

    @Test
    void blankTargetBase_onAMultiRootTarget_isRefused_whenTheSourceScopeIsUnderNoneOfItsRoots() {
        UUID linkId = linkToMultiRootTarget();
        SyncSetRequest elsewhere = new SyncSetRequest(linkId, "groups", "o=groups,dc=elsewhere", null, null, null,
                null, null, null, SyncDeletePolicy.DELETE, null, null, null, true, null);

        assertThatThrownBy(() -> service.createSet(elsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'o=groups,dc=elsewhere' isn't under any of the target directory's base DNs");
    }

    @Test
    void recheckTarget_forgetsAppliedContent_andRequestsAReconcile() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setEnabled(true);
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(membershipRepo.forgetAppliedContent(eq(setId), any())).thenReturn(4741);

        SyncRecheckResult result = service.recheckTarget(setId, "alice");

        assertThat(result.entries()).isEqualTo(4741);
        assertThat(result.reconcileQueued()).isTrue();
        verify(setRepo).requestReconcileOfSet(setId);
    }

    @Test
    void recheckTarget_onADisabledSetOrLink_waitsForThemToBeEnabled() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setLinkId(linkId);
        set.setEnabled(false);
        SyncLink link = new SyncLink();
        link.setEnabled(true);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        org.mockito.Mockito.lenient().when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));

        assertThat(service.recheckTarget(setId, "alice").reconcileQueued()).isFalse();

        set.setEnabled(true);
        link.setEnabled(false);
        assertThat(service.recheckTarget(setId, "alice").reconcileQueued()).isFalse();
    }

    @Test
    void recheckTarget_unknownSet_isNotFound() {
        UUID setId = UUID.randomUUID();
        when(setRepo.findById(setId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recheckTarget(setId, "alice"))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);
        verify(membershipRepo, never()).forgetAppliedContent(any(), any());
    }

    @Test
    void deleteSet_deletesThenAuditsAgainstTheTargetDirectory_withWhatWasDiscarded() {
        UUID setId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(setId);
        set.setName("people");
        set.setLinkId(linkId);
        set.setTargetBaseDn("ou=Users,dc=dst");
        UUID sourceDir = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(setRepo.findAll()).thenReturn(List.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(membershipRepo.countBySyncSetId(setId)).thenReturn(876L);
        when(membershipRepo.countBySyncSetIdAndState(setId, com.ldapportal.entity.enums.MembershipState.REVIEW))
                .thenReturn(3L);
        when(reconcileRunRepo.countBySyncSetId(setId)).thenReturn(12L);

        service.deleteSet(setId, admin);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(setRepo, auditService);
        order.verify(setRepo).delete(set);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("syncSetName", "people");
        detail.put("sourceDirectory", "HR LDAP");
        detail.put("targetDirectory", "Corp AD");
        detail.put("syncSetId", setId.toString());
        detail.put("linkId", linkId.toString());
        detail.put("managedEntries", 876L);
        detail.put("pendingReview", 3L);
        detail.put("reconcileRuns", 12L);
        order.verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.SYNC_SET_DELETE),
                eq("ou=Users,dc=dst"), eq(detail));
    }

    @Test
    void deleteLink_deletesThenAuditsAgainstTheTargetDirectory_withItsChangelogPosition() {
        UUID linkId = UUID.randomUUID();
        UUID sourceDir = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        SyncLink link = new SyncLink();
        link.setDisplayName("hr->corp");
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        link.setCaptureMode(com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG);
        link.setChangelogFormat(com.ldapportal.entity.enums.ChangelogFormat.values()[0]);
        link.setChangelogBaseDn("cn=changelog");
        link.setChangelogLastChangeNumber(4242L);
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(setRepo.findAllByLinkId(linkId)).thenReturn(List.of());
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");

        service.deleteLink(linkId, admin);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(linkRepo, auditService);
        order.verify(linkRepo).delete(link);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        detail.put("linkName", "hr->corp");
        detail.put("sourceDirectory", "HR LDAP");
        detail.put("targetDirectory", "Corp AD");
        detail.put("sourceDirId", sourceDir.toString());
        detail.put("targetDirId", targetDir.toString());
        detail.put("captureMode", "CHANGELOG");
        detail.put("changelogFormat", com.ldapportal.entity.enums.ChangelogFormat.values()[0].toString());
        detail.put("changelogBaseDn", "cn=changelog");
        detail.put("changelogLastChangeNumber", 4242L);
        order.verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.SYNC_LINK_DELETE),
                isNull(), eq(detail));
    }

    @Test
    void deleteLink_withSetsStillOnIt_isRefused_andNotAudited() {
        UUID linkId = UUID.randomUUID();
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(new SyncLink()));
        when(setRepo.findAllByLinkId(linkId)).thenReturn(List.of(new SyncSet()));

        assertThatThrownBy(() -> service.deleteLink(linkId,
                new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync sets");
        verify(linkRepo, never()).delete(any(SyncLink.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteImpact_countsWhatWouldBeDiscarded_andNamesTheOtherSets() {
        UUID setId = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(setId);
        set.setName("people");
        SyncSet groups = new SyncSet();
        groups.setId(UUID.randomUUID());
        groups.setName("groups");
        SyncSet admins = new SyncSet();
        admins.setId(UUID.randomUUID());
        admins.setName("Admins");
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(setRepo.findAll()).thenReturn(List.of(groups, set, admins));
        when(membershipRepo.countBySyncSetId(setId)).thenReturn(5L);
        when(membershipRepo.countBySyncSetIdAndState(setId, com.ldapportal.entity.enums.MembershipState.REVIEW))
                .thenReturn(0L);
        when(reconcileRunRepo.countBySyncSetId(setId)).thenReturn(2L);

        var impact = service.deleteImpact(setId);

        assertThat(impact.name()).isEqualTo("people");
        assertThat(impact.managed()).isEqualTo(5);
        assertThat(impact.pendingReview()).isZero();
        assertThat(impact.reconcileRuns()).isEqualTo(2);
        assertThat(impact.otherSets()).containsExactly("Admins", "groups");
    }

    @Test
    void approveDelete_auditsTheTargetDelete() {
        UUID setId = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        UUID linkId = UUID.randomUUID();
        UUID sourceDir = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setName("people");
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(targetDir);
        when(setRepo.findById(setId)).thenReturn(Optional.of(set));
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        stubDirectoryName(sourceDir, "HR LDAP");
        stubDirectoryName(targetDir, "Corp AD");
        when(engine.approveDelete(setId, "id-1")).thenReturn(new SyncDeleteApproval(
                SyncPreviewAction.DELETE, targetDir, "uid=a,dc=dst", null));

        SyncDeleteApproval result = service.approveDelete(setId, "id-1", admin);

        assertThat(result.outcome()).isEqualTo(SyncPreviewAction.DELETE);
        verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.ENTRY_DELETE), eq("uid=a,dc=dst"),
                eq(java.util.Map.of("source", "directory-sync", "operation", "approved-review-delete",
                        "syncSetName", "people", "sourceDirectory", "HR LDAP", "targetDirectory", "Corp AD",
                        "syncSetId", setId.toString(), "identity", "id-1")));
    }

    @Test
    void approveDelete_doesNotAudit_whenNothingWasDeleted() {
        UUID setId = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(engine.approveDelete(setId, "id-1")).thenReturn(new SyncDeleteApproval(
                SyncPreviewAction.ERROR, UUID.randomUUID(), "uid=a,dc=dst", "delete failed: 50"));

        service.approveDelete(setId, "id-1", admin);

        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void listMemberships_lowercasesAndWrapsSearchTerm() {
        when(setRepo.findById(any())).thenReturn(Optional.of(new SyncSet()));
        when(membershipRepo.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMemberships(UUID.randomUUID(), MembershipState.FAILED, "  Alice  ",
                PageRequest.of(0, 50));

        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(membershipRepo).search(any(), eq(MembershipState.FAILED), q.capture(), any());
        assertThat(q.getValue()).isEqualTo("%alice%");
    }

    @Test
    void listMemberships_blankSearchTermBecomesNull() {
        when(setRepo.findById(any())).thenReturn(Optional.of(new SyncSet()));
        when(membershipRepo.search(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listMemberships(UUID.randomUUID(), null, "   ", PageRequest.of(0, 50));

        verify(membershipRepo).search(any(), isNull(), isNull(), any());
    }

    // ── link capture config ──────────────────────────────────────────────────

    private SyncLinkRequest linkReq(SyncCaptureMode mode, ChangelogFormat format, String baseDn) {
        return new SyncLinkRequest("link", SRC_DIR, DST_DIR, true, mode, format, baseDn);
    }

    @Test
    void createLink_rejectsChangelogFormatWithoutSyncCapture() {
        when(directoryRepo.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> service.createLink(
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OPENLDAP_ACCESSLOG, "cn=accesslog")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported for CHANGELOG capture");
        verify(linkRepo, never()).save(any());
    }

    @Test
    void updateLink_switchingFeed_resetsCursorAndHealth() {
        SyncLink existing = changelogLink(ChangelogFormat.DSEE_CHANGELOG, "cn=changelog");
        existing.setChangelogHealth(SyncChangelogHealth.STALLED);
        existing.setChangelogLastError("old feed error");
        existing.setChangelogLastErrorAt(java.time.OffsetDateTime.now());
        existing.setChangelogLastPolledAt(java.time.OffsetDateTime.now());
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // A DSEE changeNumber is meaningless as an OUD cookie.
        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog"));

        assertThat(existing.getChangelogFormat()).isEqualTo(ChangelogFormat.OUD_COOKIE_CHANGELOG);
        assertThat(existing.getChangelogCursorToken()).isNull();
        assertThat(existing.getChangelogLastChangeNumber()).isNull();
        assertThat(existing.getChangelogSourceLastChangeNumber()).isNull();
        // The old feed's health/error no longer describe this link.
        assertThat(existing.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
        assertThat(existing.getChangelogLastError()).isNull();
        assertThat(existing.getChangelogLastErrorAt()).isNull();
        assertThat(existing.getChangelogLastPolledAt()).isNull();
    }

    @Test
    void updateLink_whitespaceOnlyBaseDnEdit_keepsCursor_andStoresTrimmed() {
        SyncLink existing = changelogLink(ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog");
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "  cn=changelog "));

        assertThat(existing.getChangelogBaseDn()).isEqualTo("cn=changelog");
        assertThat(existing.getChangelogCursorToken()).isEqualTo("42");
    }

    @Test
    void updateLink_sameFeed_keepsCursor() {
        SyncLink existing = changelogLink(ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog");
        existing.setChangelogCursorToken("dc=example,dc=com:0000018c2f3a1b2c00010000000a;");
        when(directoryRepo.existsById(any())).thenReturn(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(existing.getId(),
                linkReq(SyncCaptureMode.CHANGELOG, ChangelogFormat.OUD_COOKIE_CHANGELOG, "cn=changelog"));

        assertThat(existing.getChangelogCursorToken())
                .isEqualTo("dc=example,dc=com:0000018c2f3a1b2c00010000000a;");
    }

    private static final UUID SRC_DIR = UUID.randomUUID();
    private static final UUID DST_DIR = UUID.randomUUID();

    private static SyncLink changelogLink(ChangelogFormat format, String baseDn) {
        SyncLink l = new SyncLink();
        l.setId(UUID.randomUUID());
        l.setSourceDirId(SRC_DIR);
        l.setTargetDirId(DST_DIR);
        l.setCaptureMode(SyncCaptureMode.CHANGELOG);
        l.setChangelogFormat(format);
        l.setChangelogBaseDn(baseDn);
        l.setChangelogCursorToken("42");
        l.setChangelogLastChangeNumber(42L);
        l.setChangelogSourceLastChangeNumber(50L);
        return l;
    }

    private void stubDirectoryName(UUID id, String name) {
        com.ldapportal.entity.DirectoryConnection dc = new com.ldapportal.entity.DirectoryConnection();
        dc.setDisplayName(name);
        when(directoryRepo.findById(id)).thenReturn(Optional.of(dc));
    }
}
