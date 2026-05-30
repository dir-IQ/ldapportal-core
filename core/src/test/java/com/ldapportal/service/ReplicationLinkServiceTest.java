// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.AuditAction;
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
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private AuditService               auditService;
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

        List<ReplicationLinkResponse> resp = service.listLinks();

        ReplicationLinkResponse a = resp.stream().filter(r -> r.id().equals(linkA.getId())).findFirst().orElseThrow();
        ReplicationLinkResponse b = resp.stream().filter(r -> r.id().equals(linkB.getId())).findFirst().orElseThrow();
        assertThat(a.pendingCount()).isEqualTo(3);
        assertThat(a.failedCount()).isEqualTo(1);
        assertThat(a.lastDeliveredAt()).isEqualTo(now);
        assertThat(b.deadLetteredCount()).isEqualTo(2);
        assertThat(b.lastDeliveredAt()).isNull();
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

    // ── helpers ──────────────────────────────────────────────────────────────

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
