// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncPreviewAction;
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
    @Mock private MembershipReconciler reconciler;
    @Mock private SyncContentVerifier verifier;
    @Mock private RecomputeEnqueuer enqueuer;
    @Mock private com.ldapportal.ldap.sync.SyncPreviewService previewService;
    @Mock private com.ldapportal.ldap.sync.SyncReconcileHistory reconcileHistory;
    @Mock private com.ldapportal.ldap.sync.RecomputeEngine engine;
    @Mock private AuditService auditService;

    private SyncConfigService service;

    @BeforeEach
    void setUp() {
        service = new SyncConfigService(linkRepo, setRepo, membershipRepo, directoryRepo,
                reconciler, verifier, enqueuer, previewService, reconcileHistory, engine, auditService);
    }

    private SyncSetRequest req(List<SyncTransformRule> rules) {
        return new SyncSetRequest(UUID.randomUUID(), "people", null, null, null, null, null, null, null,
                SyncDeletePolicy.DELETE, rules, null, null, true);
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
    void approveDelete_auditsTheTargetDelete() {
        UUID setId = UUID.randomUUID();
        UUID targetDir = UUID.randomUUID();
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");
        when(setRepo.findById(setId)).thenReturn(Optional.of(new SyncSet()));
        when(engine.approveDelete(setId, "id-1")).thenReturn(new SyncDeleteApproval(
                SyncPreviewAction.DELETE, targetDir, "uid=a,dc=dst", null));

        SyncDeleteApproval result = service.approveDelete(setId, "id-1", admin);

        assertThat(result.outcome()).isEqualTo(SyncPreviewAction.DELETE);
        verify(auditService).record(eq(admin), eq(targetDir), eq(AuditAction.ENTRY_DELETE), eq("uid=a,dc=dst"),
                eq(java.util.Map.of("source", "directory-sync", "operation", "approved-review-delete",
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
}
