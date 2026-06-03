// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplicationLinkServiceTest {

    @Mock private ReplicationLinkRepository  linkRepo;
    @Mock private ReplicationEventRepository eventRepo;
    @Mock private com.ldapportal.repository.ReconciliationFindingRepository findingRepo;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private AuditService               auditService;
    @Mock private com.ldapportal.ldap.replication.reconcile.ReconciliationService reconciliationService;
    @InjectMocks private ReplicationLinkService service;

    private final AuthPrincipal principal =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");

    @Test
    void create_buildsLinkAndPersists() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            l.setCreatedAt(OffsetDateTime.now());
            l.setUpdatedAt(OffsetDateTime.now());
            return l;
        });

        ReplicationLinkResponse resp = service.createLink(principal, new ReplicationLinkRequest(
                "Acme → Backup",
                source.getId(), target.getId(),
                null, null,
                true, false, List.of()));

        assertThat(resp.displayName()).isEqualTo("Acme → Backup");
        assertThat(resp.sourceDirectoryId()).isEqualTo(source.getId());
        assertThat(resp.targetDirectoryId()).isEqualTo(target.getId());
        assertThat(resp.sourceBaseDn()).isNull();
        assertThat(resp.targetBaseDn()).isNull();
        assertThat(resp.enabled()).isTrue();
        // Freshly-created link reports zero counts — health rollup
        // hasn't been computed at create-time (it's the empty default).
        assertThat(resp.pendingCount()).isZero();
        assertThat(resp.deadLetteredCount()).isZero();
    }

    @Test
    void create_rejectsSameSourceAndTarget() {
        DirectoryConnection one = directory("One");
        // No need to stub dirRepo lookups — validation fires before
        // the helpers are called.

        assertThatThrownBy(() -> service.createLink(principal, new ReplicationLinkRequest(
                "Self loop", one.getId(), one.getId(),
                null, null, true, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void create_rejectsHalfSetBaseDn() {
        // Both base DNs must be set or both null. Catching the
        // half-set case at the service layer gives a clean 400
        // instead of a constraint-violation 500.
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal, new ReplicationLinkRequest(
                "Mixed",
                source.getId(), target.getId(),
                "dc=src,dc=com", null,    // only source set
                true, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must both be set or both null");
    }

    @Test
    void create_rejectsReverseOfExistingLink_regardlessOfEnabledState() {
        // A→B is requested while a B→A link already exists. The guard
        // must reject even though the existing reverse link is DISABLED:
        // a paused reverse link can be re-enabled later to re-arm a loop.
        DirectoryConnection source = directory("A");
        DirectoryConnection target = directory("B");
        ReplicationLink reverse = link("B → A");
        reverse.setEnabled(false);                  // disabled — must still block
        // The guard queries for the reverse pair (target, source).
        when(linkRepo.findFirstBySourceDirectoryIdAndTargetDirectoryId(
                target.getId(), source.getId())).thenReturn(Optional.of(reverse));

        assertThatThrownBy(() -> service.createLink(principal, new ReplicationLinkRequest(
                "A → B", source.getId(), target.getId(),
                null, null, true, false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reverse of existing link");

        verify(linkRepo, never()).save(any());
        verify(auditService, never()).recordSystemEvent(any(), any(), any());
    }

    @Test
    void list_attachesHealthCountsFromRollup() {
        // Two links — service should call findHealthRollup once with
        // both IDs and attach the returned counts. Pinning the batched
        // query contract so a future "fetch per link" regression
        // doesn't slip through.
        ReplicationLink linkA = link("A");
        ReplicationLink linkB = link("B");
        when(linkRepo.findAll()).thenReturn(List.of(linkA, linkB));

        OffsetDateTime now = OffsetDateTime.now();
        when(eventRepo.findHealthRollup(any())).thenReturn(List.of(
                new Object[]{ linkA.getId(), 3L, 1L, 0L, now },
                new Object[]{ linkB.getId(), 0L, 0L, 2L, null }));
        // Open reconciliation findings fold into the same health rollup.
        when(findingRepo.countByLinkIdsAndStatus(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{ linkA.getId(), 4L }));

        List<ReplicationLinkResponse> resp = service.listLinks();

        ReplicationLinkResponse a = resp.stream().filter(r -> r.id().equals(linkA.getId())).findFirst().orElseThrow();
        ReplicationLinkResponse b = resp.stream().filter(r -> r.id().equals(linkB.getId())).findFirst().orElseThrow();
        assertThat(a.pendingCount()).isEqualTo(3);
        assertThat(a.failedCount()).isEqualTo(1);
        assertThat(a.lastDeliveredAt()).isEqualTo(now);
        assertThat(a.openFindingCount()).isEqualTo(4);
        assertThat(b.deadLetteredCount()).isEqualTo(2);
        assertThat(b.lastDeliveredAt()).isNull();
        assertThat(b.openFindingCount()).isZero();
    }

    // ── audit emissions ──────────────────────────────────────────────────────

    @Test
    void create_recordsLinkCreatedAudit() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        service.createLink(principal, new ReplicationLinkRequest(
                "Audit Link", source.getId(), target.getId(),
                null, null, true, false, List.of()));

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_CREATED), any());
    }

    @Test
    void update_recordsUpdatedAudit_andEnabledToggleWhenFlipped() {
        // Toggling enabled flips emits BOTH the generic UPDATED action
        // and the specific DISABLED action. Pinning the dual-emission
        // contract so a future 'only emit the toggle' regression breaks.
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        ReplicationLink existing = link("Existing");
        existing.setSourceDirectory(source);
        existing.setTargetDirectory(target);
        existing.setEnabled(true);  // was enabled
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(principal, existing.getId(), new ReplicationLinkRequest(
                "Existing", source.getId(), target.getId(),
                null, null, false, false, List.of()));  // now disabled

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_UPDATED), any());
        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_DISABLED), any());
        verify(auditService, never()).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_ENABLED), any());
    }

    @Test
    void update_noToggleEmission_whenEnabledFlagUnchanged() {
        // Rename only — no toggle audit, just the generic update.
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        ReplicationLink existing = link("OldName");
        existing.setSourceDirectory(source);
        existing.setTargetDirectory(target);
        existing.setEnabled(true);
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateLink(principal, existing.getId(), new ReplicationLinkRequest(
                "NewName", source.getId(), target.getId(),
                null, null, true, false, List.of()));

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_UPDATED), any());
        verify(auditService, never()).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_ENABLED), any());
        verify(auditService, never()).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_DISABLED), any());
    }

    @Test
    void delete_recordsLinkDeletedAudit() {
        ReplicationLink existing = link("ToDelete");
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.deleteLink(principal, existing.getId());

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_LINK_DELETED), any());
    }

    // ── reconciliation config (R-P0) ──────────────────────────────────────────

    @Test
    void create_withReconcileEnabled_setsNextRunAndAuditsConfig() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        OffsetDateTime firstRun = OffsetDateTime.now().plusHours(1);
        ReplicationLinkResponse resp = service.createLink(principal, new ReplicationLinkRequest(
                "Recon", source.getId(), target.getId(), null, null, true, false, List.of(),
                true, ReconcileMode.AUTO_CORRECT, firstRun, 7200, ReconcileDeleteAction.AUTO));

        assertThat(resp.reconcileEnabled()).isTrue();
        assertThat(resp.reconcileMode()).isEqualTo(ReconcileMode.AUTO_CORRECT);
        assertThat(resp.reconcileDeleteAction()).isEqualTo(ReconcileDeleteAction.AUTO);
        assertThat(resp.reconcileIntervalSecs()).isEqualTo(7200);
        // First-run drives the initial next-run pointer.
        assertThat(resp.reconcileNextRunAt()).isEqualTo(firstRun);
        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.RECONCILIATION_CONFIG_UPDATED), any());
    }

    @Test
    void create_reconcileDisabled_defaultsToReviewAndNoConfigAudit() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        ReplicationLinkResponse resp = service.createLink(principal, new ReplicationLinkRequest(
                "Plain", source.getId(), target.getId(), null, null, true, false, List.of()));

        assertThat(resp.reconcileEnabled()).isFalse();
        assertThat(resp.reconcileMode()).isEqualTo(ReconcileMode.REVIEW);
        assertThat(resp.reconcileDeleteAction()).isEqualTo(ReconcileDeleteAction.REVIEW);
        assertThat(resp.reconcileNextRunAt()).isNull();
        verify(auditService, never()).recordSystemEvent(
                eq(principal), eq(AuditAction.RECONCILIATION_CONFIG_UPDATED), any());
    }

    @Test
    void create_reconcileEnabled_rejectsMissingFirstRun() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal, new ReplicationLinkRequest(
                "Recon", source.getId(), target.getId(), null, null, true, false, List.of(),
                true, ReconcileMode.REVIEW, null, 7200, ReconcileDeleteAction.REVIEW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reconcileFirstRunAt is required");
    }

    @Test
    void create_reconcileEnabled_rejectsSubHourInterval() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal, new ReplicationLinkRequest(
                "Recon", source.getId(), target.getId(), null, null, true, false, List.of(),
                true, ReconcileMode.REVIEW, OffsetDateTime.now().plusHours(1), 1800, ReconcileDeleteAction.REVIEW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 3600");
    }

    @Test
    void update_enablingReconcile_auditsConfigUpdatedAndSetsNextRun() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        ReplicationLink existing = link("Existing");
        existing.setSourceDirectory(source);
        existing.setTargetDirectory(target);
        existing.setEnabled(true);  // already enabled, so no link-toggle audit noise
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OffsetDateTime firstRun = OffsetDateTime.now().plusHours(2);
        ReplicationLinkResponse resp = service.updateLink(principal, existing.getId(),
                new ReplicationLinkRequest("Existing", source.getId(), target.getId(),
                        null, null, true, false, List.of(),
                        true, ReconcileMode.REVIEW, firstRun, 3600, ReconcileDeleteAction.REVIEW));

        assertThat(resp.reconcileEnabled()).isTrue();
        assertThat(resp.reconcileNextRunAt()).isEqualTo(firstRun);
        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.RECONCILIATION_CONFIG_UPDATED), any());
    }

    @Test
    void update_unrelatedEdit_preservesScheduleAndSkipsConfigAudit() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        OffsetDateTime firstRun = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime nextRun = firstRun.plusHours(1);
        ReplicationLink existing = link("OldName");
        existing.setSourceDirectory(source);
        existing.setTargetDirectory(target);
        existing.setEnabled(true);
        existing.setReconcileEnabled(true);
        existing.setReconcileMode(ReconcileMode.REVIEW);
        existing.setReconcileDeleteAction(ReconcileDeleteAction.REVIEW);
        existing.setReconcileFirstRunAt(firstRun);
        existing.setReconcileIntervalSecs(3600);
        existing.setReconcileNextRunAt(nextRun);   // schedule already advanced once
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Rename only — reconcile config resent unchanged.
        ReplicationLinkResponse resp = service.updateLink(principal, existing.getId(),
                new ReplicationLinkRequest("NewName", source.getId(), target.getId(),
                        null, null, true, false, List.of(),
                        true, ReconcileMode.REVIEW, firstRun, 3600, ReconcileDeleteAction.REVIEW));

        // Running schedule preserved (next-run not reset to first-run).
        assertThat(resp.reconcileNextRunAt()).isEqualTo(nextRun);
        verify(auditService, never()).recordSystemEvent(
                eq(principal), eq(AuditAction.RECONCILIATION_CONFIG_UPDATED), any());
    }

    // ── changelog capture config (C1) ──────────────────────────────────────────

    @Test
    void create_changelogMode_defaultsBaseDnAndExposesConfig() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        ReplicationLinkResponse resp = service.createLink(principal, changelogRequest(
                source, target, ChangelogFormat.DSEE_CHANGELOG, "  ", null));  // blank base DN

        assertThat(resp.captureMode()).isEqualTo(ReplicationCaptureMode.CHANGELOG);
        assertThat(resp.changelogFormat()).isEqualTo(ChangelogFormat.DSEE_CHANGELOG);
        // Blank base DN defaults to cn=changelog.
        assertThat(resp.changelogBaseDn()).isEqualTo("cn=changelog");
        // No poll has run yet, so cursor / head / lag are unknown.
        assertThat(resp.changelogLastChangeNumber()).isNull();
        assertThat(resp.changelogLag()).isNull();
    }

    @Test
    void create_changelogMode_rejectsMissingFormat() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal,
                changelogRequest(source, target, null, "cn=changelog", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changelogFormat is required");
    }

    @Test
    void create_changelogMode_rejectsUnsupportedFormat() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal,
                changelogRequest(source, target, ChangelogFormat.OPENLDAP_ACCESSLOG, "cn=changelog", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DSEE_CHANGELOG");
    }

    @Test
    void create_rejectsUnparseableExcludeFilter() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");

        assertThatThrownBy(() -> service.createLink(principal,
                changelogRequest(source, target, ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", "(((not a filter")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excludeFilter is not a valid");
    }

    @Test
    void create_appIntercept_nullsChangelogConfig() {
        // Even if a client sends changelog config alongside APP_INTERCEPT, it
        // must be nulled out (the DB CHECK forbids it on APP_INTERCEPT rows).
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        ReplicationLinkResponse resp = service.createLink(principal, new ReplicationLinkRequest(
                "App", source.getId(), target.getId(), null, null, true, false, List.of(),
                false, null, null, null, null,
                ReplicationCaptureMode.APP_INTERCEPT, ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", null));

        assertThat(resp.captureMode()).isEqualTo(ReplicationCaptureMode.APP_INTERCEPT);
        assertThat(resp.changelogFormat()).isNull();
        assertThat(resp.changelogBaseDn()).isNull();
    }

    @Test
    void update_switchingCaptureMode_resetsCursor() {
        // An existing CHANGELOG link with an advanced cursor; switching it to
        // APP_INTERCEPT must reset the cursor and health so a later switch
        // back re-seeds cleanly (§2.1).
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        ReplicationLink existing = link("Changelog Link");
        existing.setSourceDirectory(source);
        existing.setTargetDirectory(target);
        existing.setCaptureMode(ReplicationCaptureMode.CHANGELOG);
        existing.setChangelogFormat(ChangelogFormat.DSEE_CHANGELOG);
        existing.setChangelogBaseDn("cn=changelog");
        existing.setChangelogLastChangeNumber(4242L);
        existing.setChangelogSourceLastChangeNumber(4250L);
        existing.setChangelogPollClaimedAt(OffsetDateTime.now());  // a poll lease held under the old mode
        when(linkRepo.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReplicationLinkResponse resp = service.updateLink(principal, existing.getId(),
                new ReplicationLinkRequest("Changelog Link", source.getId(), target.getId(),
                        null, null, true, false, List.of(),
                        false, null, null, null, null,
                        ReplicationCaptureMode.APP_INTERCEPT, null, null, null));

        assertThat(resp.captureMode()).isEqualTo(ReplicationCaptureMode.APP_INTERCEPT);
        assertThat(resp.changelogLastChangeNumber()).isNull();
        assertThat(resp.changelogSourceLastChangeNumber()).isNull();
        // The poll lease is released too, so a later switch back isn't blocked
        // by a stale claim (not exposed on the response — assert on the entity).
        assertThat(existing.getChangelogPollClaimedAt()).isNull();
    }

    @Test
    void create_enabledChangelogLink_triggersSeamReconcile() {
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        service.createLink(principal, changelogRequest(
                source, target, ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", null));

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_CHANGELOG_CAPTURE_ENABLED), any());
        verify(reconciliationService).trigger(
                any(), eq(com.ldapportal.entity.enums.ReconciliationRunTrigger.MANUAL), eq(principal));
    }

    @Test
    void create_disabledChangelogLink_auditsButDoesNotReconcile() {
        // A disabled link must not auto-reconcile — corrective events would pile
        // up and flood the target when it's later enabled.
        DirectoryConnection source = directory("Source");
        DirectoryConnection target = directory("Target");
        when(dirRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(dirRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(linkRepo.save(any())).thenAnswer(inv -> {
            ReplicationLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            return l;
        });

        service.createLink(principal, new ReplicationLinkRequest(
                "Disabled CL", source.getId(), target.getId(), null, null,
                false, false, List.of(),          // enabled = false
                false, null, null, null, null,
                ReplicationCaptureMode.CHANGELOG, ChangelogFormat.DSEE_CHANGELOG, "cn=changelog", null));

        verify(auditService).recordSystemEvent(
                eq(principal), eq(AuditAction.REPLICATION_CHANGELOG_CAPTURE_ENABLED), any());
        verify(reconciliationService, never()).trigger(any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReplicationLinkRequest changelogRequest(DirectoryConnection source, DirectoryConnection target,
                                                    ChangelogFormat format, String baseDn, String excludeFilter) {
        return new ReplicationLinkRequest(
                "Changelog", source.getId(), target.getId(), null, null, true, false, List.of(),
                false, null, null, null, null,
                ReplicationCaptureMode.CHANGELOG, format, baseDn, excludeFilter);
    }

    private DirectoryConnection directory(String displayName) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName(displayName);
        return dc;
    }

    private ReplicationLink link(String displayName) {
        ReplicationLink l = new ReplicationLink();
        l.setId(UUID.randomUUID());
        l.setDisplayName(displayName);
        l.setSourceDirectory(directory("S-" + displayName));
        l.setTargetDirectory(directory("T-" + displayName));
        l.setEnabled(true);
        return l;
    }
}
