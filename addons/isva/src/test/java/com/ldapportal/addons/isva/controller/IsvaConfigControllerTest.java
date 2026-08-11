// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigDto;
import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.addons.isva.service.IsvaConfigProbeService;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level tests for the controller's logic. The @Entitled and
 * @PreAuthorize annotations are framework concerns covered by
 * Spring's test infrastructure higher up; here we focus on the
 * GET/PUT/probe behaviour and the linked-mode validation.
 */
@ExtendWith(MockitoExtension.class)
class IsvaConfigControllerTest {

    @Mock private VendorIntegrationIsvaConfigRepository configRepo;
    @Mock private DirectoryConnectionRepository directoryRepo;
    @Mock private IsvaConfigProbeService probeService;

    private IsvaConfigService service;
    private IsvaConfigController controller;
    private IsvaConfigBySlugController bySlugController;
    private UUID directoryId;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new IsvaConfigService(configRepo, directoryRepo, probeService);
        controller = new IsvaConfigController(service);
        bySlugController = new IsvaConfigBySlugController(service);
        directoryId = UUID.randomUUID();
        principal = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "alice");
        lenient().when(directoryRepo.existsById(directoryId)).thenReturn(true);
        lenient().when(directoryRepo.findById(directoryId))
                .thenReturn(Optional.of(new DirectoryConnection()));
    }

    // ── GET ────────────────────────────────────────────────────────

    @Test
    void get_returnsDto_whenConfigExists() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setVersion(0L);
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.INLINE);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));

        IsvaConfigDto body = controller.get(directoryId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.enabled()).isTrue();
        assertThat(body.topologyMode()).isEqualTo(IsvaTopologyMode.INLINE);
    }

    @Test
    void get_404_whenNoConfigRow() {
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(directoryId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PUT a config to create one");
    }

    @Test
    void get_404_whenDirectoryMissing() {
        when(directoryRepo.existsById(directoryId)).thenReturn(false);

        assertThatThrownBy(() -> controller.get(directoryId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Directory not found");
    }

    // ── PUT (upsert) ──────────────────────────────────────────────

    @Test
    void upsert_inlineMode_createsRowWithDefaults() {
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());
        when(configRepo.saveAndFlush(any(VendorIntegrationIsvaConfig.class)))
                .thenAnswer(IsvaConfigControllerTest::saveAnswer);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", "Default",
                100, true,
                null, null, null, null, null, null);

        IsvaConfigDto body = controller.upsert(directoryId, null, principal, req).getBody();

        assertThat(body).isNotNull();
        assertThat(body.enabled()).isTrue();
        assertThat(body.topologyMode()).isEqualTo(IsvaTopologyMode.INLINE);
        assertThat(body.secLoginType()).isEqualTo("Default");
        // INLINE mode → linked-mode fields cleared.
        assertThat(body.managementDitBaseDn()).isNull();
        assertThat(body.updatedBy()).isEqualTo("alice");
    }

    @Test
    void upsert_linkedMode_populatesLinkedFields() {
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());
        when(configRepo.saveAndFlush(any(VendorIntegrationIsvaConfig.class)))
                .thenAnswer(IsvaConfigControllerTest::saveAnswer);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.LINKED, "Default", "Default",
                100, true,
                List.of("secUser", "eUser"),
                List.of("secLogin", "secAcctValid", "secPwdValid",
                        "secValidUntil", "secPwdLastChanged"),
                "secAuthority=Default,o=acme,c=us",
                "principalName",
                IsvaRdnValueSource.UID,
                IsvaGroupMemberTarget.DEMOGRAPHIC_DN);

        IsvaConfigDto body = controller.upsert(directoryId, null, principal, req).getBody();

        assertThat(body).isNotNull();
        assertThat(body.topologyMode()).isEqualTo(IsvaTopologyMode.LINKED);
        assertThat(body.managementDitBaseDn()).isEqualTo("secAuthority=Default,o=acme,c=us");
        assertThat(body.secuserObjectClasses()).containsExactly("secUser", "eUser");
        assertThat(body.secuserOverlayAttributes()).containsExactly(
                "secLogin", "secAcctValid", "secPwdValid",
                "secValidUntil", "secPwdLastChanged");
        assertThat(body.secuserRdnAttribute()).isEqualTo("principalName");
        assertThat(body.secuserRdnValueSource()).isEqualTo(IsvaRdnValueSource.UID);
        assertThat(body.groupMemberTarget()).isEqualTo(IsvaGroupMemberTarget.DEMOGRAPHIC_DN);
    }

    @Test
    void upsert_linkedMode_blankManagementDit_400() {
        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.LINKED, "Default", "Default",
                100, true,
                null,   // objectClasses → normalized to [secUser]
                null,   // overlay attrs → normalized to default set
                "   ",   // blank → invalid
                "secUUID", null, null);

        assertThatThrownBy(() -> controller.upsert(directoryId, null, principal, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managementDitBaseDn is required");
    }

    @Test
    void upsert_topologyFlipFromLinkedToInline_clearsManagementDit() {
        // Existing row is LINKED; PUT with INLINE should null out
        // the linked-mode fields so a stale base DN doesn't linger.
        VendorIntegrationIsvaConfig existing = new VendorIntegrationIsvaConfig();
        existing.setDirectoryConnectionId(directoryId);
        existing.setEnabled(true);
        existing.setTopologyMode(IsvaTopologyMode.LINKED);
        existing.setManagementDitBaseDn("secAuthority=Default,o=acme,c=us");
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(existing));
        when(configRepo.saveAndFlush(any(VendorIntegrationIsvaConfig.class)))
                .thenAnswer(IsvaConfigControllerTest::saveAnswer);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", "Default",
                100, true,
                null, null, null, null, null, null);

        IsvaConfigDto body = controller.upsert(directoryId, null, principal, req).getBody();
        assertThat(body).isNotNull();
        assertThat(body.managementDitBaseDn()).isNull();

        ArgumentCaptor<VendorIntegrationIsvaConfig> captor =
                ArgumentCaptor.forClass(VendorIntegrationIsvaConfig.class);
        verify(configRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getManagementDitBaseDn()).isNull();
    }

    @Test
    void upsert_directoryMissing_404() {
        when(directoryRepo.existsById(directoryId)).thenReturn(false);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", "Default",
                100, true,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.upsert(directoryId, null, principal, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void upsert_secAuthorityBlank_storesAsNull() {
        // Blank string in the request is more confusing than NULL on
        // the DB side — controller normalises.
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());
        when(configRepo.saveAndFlush(any(VendorIntegrationIsvaConfig.class)))
                .thenAnswer(IsvaConfigControllerTest::saveAnswer);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "  ", "Default",
                100, true,
                null, null, null, null, null, null);

        ArgumentCaptor<VendorIntegrationIsvaConfig> captor =
                ArgumentCaptor.forClass(VendorIntegrationIsvaConfig.class);
        controller.upsert(directoryId, null, principal, req);
        verify(configRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSecAuthority()).isNull();
    }

    // ── POST /probe ───────────────────────────────────────────────

    @Test
    void probe_delegates_andReturnsResult() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setVersion(0L);
        cfg.setTopologyMode(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn("secAuthority=Default,o=x,c=y");
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));
        when(probeService.probe(any(DirectoryConnection.class), any(VendorIntegrationIsvaConfig.class)))
                .thenReturn(new ProbeResult(true, true, true,
                        List.of(), List.of(), List.of("OK")));

        ProbeResult body = controller.probe(directoryId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.reachable()).isTrue();
        assertThat(body.sampleSecUserFound()).isTrue();
        assertThat(body.warnings()).containsExactly("OK");
    }

    @Test
    void probe_requiresConfigToExist() {
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.probe(directoryId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("save a config before probing");
    }

    @Test
    void probe_404_whenDirectoryMissing() {
        when(directoryRepo.findById(directoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.probe(directoryId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Directory not found");
    }

    // ── by-slug (IaC) ─────────────────────────────────────────────────

    @Test
    void getBySlug_resolvesSlug_andReturnsConfig() {
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(directoryId);
        when(directoryRepo.findBySlug("corp-ldap")).thenReturn(Optional.of(dir));

        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setVersion(0L);
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.INLINE);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));

        IsvaConfigDto body = bySlugController.get("corp-ldap").getBody();

        assertThat(body).isNotNull();
        assertThat(body.enabled()).isTrue();
    }

    @Test
    void upsertBySlug_resolvesSlug_andUpserts() {
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(directoryId);
        when(directoryRepo.findBySlug("corp-ldap")).thenReturn(Optional.of(dir));
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());
        when(configRepo.saveAndFlush(any(VendorIntegrationIsvaConfig.class)))
                .thenAnswer(IsvaConfigControllerTest::saveAnswer);

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", "Default",
                100, true,
                null, null, null, null, null, null);

        IsvaConfigDto body = bySlugController.upsert("corp-ldap", null, principal, req).getBody();

        assertThat(body).isNotNull();
        assertThat(body.enabled()).isTrue();
        assertThat(body.updatedBy()).isEqualTo("alice");

        ArgumentCaptor<VendorIntegrationIsvaConfig> captor =
                ArgumentCaptor.forClass(VendorIntegrationIsvaConfig.class);
        verify(configRepo).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDirectoryConnectionId()).isEqualTo(directoryId);
    }

    @Test
    void bySlug_unknownSlug_404() {
        when(directoryRepo.findBySlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bySlugController.get("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("slug [nope]");
    }

    // ── concurrency (ETag / If-Match) ─────────────────────────────────────────

    @Test
    void get_carriesVersionETag() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setVersion(4L);
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.INLINE);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));

        assertThat(controller.get(directoryId).getHeaders().getETag()).isEqualTo("\"4\"");
    }

    @Test
    void upsert_staleIfMatch_throwsPreconditionFailed() {
        VendorIntegrationIsvaConfig existing = new VendorIntegrationIsvaConfig();
        existing.setDirectoryConnectionId(directoryId);
        existing.setVersion(5L);
        existing.setTopologyMode(IsvaTopologyMode.INLINE);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(existing));

        UpsertIsvaConfigRequest req = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", "Default",
                100, true,
                null, null, null, null, null, null);

        // If-Match "1" parses to expectedVersion 1, but the row is at 5.
        assertThatThrownBy(() -> controller.upsert(directoryId, "\"1\"", principal, req))
                .isInstanceOf(com.ldapportal.exception.PreconditionFailedException.class);
        verify(configRepo, org.mockito.Mockito.never())
                .saveAndFlush(any(VendorIntegrationIsvaConfig.class));
    }

    /** Mock-side mimic of a JPA flush: assigns version 0 to a freshly persisted row. */
    private static VendorIntegrationIsvaConfig saveAnswer(org.mockito.invocation.InvocationOnMock inv) {
        VendorIntegrationIsvaConfig e = inv.getArgument(0);
        if (e.getVersion() == null) {
            e.setVersion(0L);
        }
        return e;
    }
}
