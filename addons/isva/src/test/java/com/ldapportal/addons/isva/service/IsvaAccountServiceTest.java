// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaSecUserPlans;
import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.dto.IsvaRevokeMode;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.DeleteStep;
import com.ldapportal.core.provisioning.LdapOperationStep;
import com.ldapportal.core.provisioning.ModifyStep;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.ldapportal.service.ProvisioningProfileService;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-unit verb tests for {@link IsvaAccountService}. The probe and
 * the executor are mocked so the cases stay focused on guard logic,
 * idempotency policy, audit shape, and TOCTOU translation — the
 * probe's own behaviour is covered by
 * {@code IsvaAccountStatusProbeIntegrationTest} against an in-memory
 * directory.
 *
 * <p>{@link IsvaSecUserPlans} is real (not mocked) because its
 * fragments are pure functions and asserting the actual emitted
 * {@link ModifyStep}/{@link AddStep}/{@link DeleteStep} is cheaper
 * than maintaining mock-step return values.</p>
 */
@ExtendWith(MockitoExtension.class)
class IsvaAccountServiceTest {

    private static final UUID DIR_ID = UUID.randomUUID();
    private static final String DEMOGRAPHIC_DN = "uid=alice,ou=people,dc=example,dc=com";
    private static final String SECUSER_DN = "secUUID=abc-123,secAuthority=Default,o=acme,c=us";
    private static final String MGMT_BASE = "secAuthority=Default,o=acme,c=us";

    @Mock private VendorIntegrationIsvaConfigRepository configRepo;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private IsvaAccountStatusProbe probe;
    @Mock private PlanExecutor planExecutor;
    @Mock private ProvisioningProfileService profileService;
    @Mock private IsvaProfileOverrideService overrideService;
    @Mock private AuditService auditService;

    private final IsvaSecUserPlans secUserPlans = new IsvaSecUserPlans();
    private IsvaAccountService service;

    private final AuthPrincipal principal = new AuthPrincipal(
            PrincipalType.ADMIN, UUID.randomUUID(), "ops-alice");

    @BeforeEach
    void setUp() {
        service = new IsvaAccountService(
                configRepo, dirRepo, secUserPlans, probe, planExecutor,
                profileService, overrideService, auditService);
    }

    // ── directory / config preamble ───────────────────────────────

    @Test
    void getStatus_unknownDirectory_404() {
        when(dirRepo.findById(DIR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(DIR_ID, DEMOGRAPHIC_DN))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Directory not found");
    }

    @Test
    void getStatus_iviaNotEnabledOnDirectory_409_iviaDirectoryDisabled() {
        givenDirectoryButNoConfig();

        assertThatThrownBy(() -> service.getStatus(DIR_ID, DEMOGRAPHIC_DN))
                .isInstanceOf(IsvaAccountRefusalException.class)
                .extracting(ex -> ((IsvaAccountRefusalException) ex).getCode())
                .isEqualTo("ivia_directory_disabled");
    }

    @Test
    void getStatus_iviaPresentButDisabled_409_iviaDirectoryDisabled() {
        givenDirectoryAndConfig(linkedConfig(false));

        assertThatThrownBy(() -> service.getStatus(DIR_ID, DEMOGRAPHIC_DN))
                .isInstanceOf(IsvaAccountRefusalException.class)
                .satisfies(ex -> {
                    IsvaAccountRefusalException r = (IsvaAccountRefusalException) ex;
                    assertThat(r.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getCode()).isEqualTo("ivia_directory_disabled");
                });
    }

    // ── grant ────────────────────────────────────────────────────

    @Test
    void grant_inline_happyPath_emitsModifyAddStep_andAuditsUserUpdate() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        givenNotForceOff();
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE),
                        // re-probe after write
                        presentInlineStatus(true, true));
        when(probe.resolveUid(any(), eq(DEMOGRAPHIC_DN))).thenReturn("alice");

        service.grant(DIR_ID, DEMOGRAPHIC_DN, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        ModifyStep step = (ModifyStep) stepCap.getValue();
        assertThat(step.targetDn()).isEqualTo(DEMOGRAPHIC_DN);
        // The grant-on-existing MODIFY adds the secUser objectClass + sec* defaults
        assertThat(step.mods()).anySatisfy(m -> {
            assertThat(m.getAttributeName()).isEqualToIgnoringCase("objectClass");
            assertThat(m.getValues()).contains("secUser");
        });

        assertAudit(AuditAction.USER_UPDATE, Map.of(
                "source", "ivia",
                "ivia_op", "grant",
                "mode", "inline"));
    }

    @Test
    void grant_linked_happyPath_emitsAddStep_andAuditsWithSecUserDn() {
        VendorIntegrationIsvaConfig cfg = linkedConfig(true);
        givenDirectoryAndConfig(cfg);
        givenNotForceOff();
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED),
                        presentLinkedStatus(SECUSER_DN, true, true));
        when(probe.resolveUid(any(), eq(DEMOGRAPHIC_DN))).thenReturn("alice");

        service.grant(DIR_ID, DEMOGRAPHIC_DN, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        AddStep step = (AddStep) stepCap.getValue();
        // grantLinked uses secUUID-based DN by default
        assertThat(step.targetDn()).startsWith("secUUID=").endsWith("," + MGMT_BASE);
        // ABORT policy — not COMPENSATE (that's for lifecycle-create).
        assertThat(step.onFailure()).isEqualTo(
                com.ldapportal.core.provisioning.StepFailurePolicy.ABORT);

        ArgumentCaptor<Map<String, Object>> detailCap = mapCaptor();
        verify(auditService).record(eq(principal), eq(DIR_ID),
                eq(AuditAction.USER_UPDATE), eq(DEMOGRAPHIC_DN), detailCap.capture());
        assertThat(detailCap.getValue())
                .containsEntry("mode", "linked")
                .containsEntry("ivia_op", "grant")
                .containsKey("secUserDn");
    }

    @Test
    void grant_alreadyLinked_409_iviaAlreadyLinked_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        givenNotForceOff();
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true));

        assertRefusal(() -> service.grant(DIR_ID, DEMOGRAPHIC_DN, principal),
                HttpStatus.CONFLICT, "ivia_already_linked");
        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    @Test
    void grant_forceOff_409_iviaForceOff_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        UUID profileId = UUID.randomUUID();
        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setId(profileId);
        when(profileService.resolveProfileForDn(eq(DIR_ID), eq(DEMOGRAPHIC_DN)))
                .thenReturn(Optional.of(profile));
        when(overrideService.isExemptFromIvia(eq(profileId))).thenReturn(true);

        assertRefusal(() -> service.grant(DIR_ID, DEMOGRAPHIC_DN, principal),
                HttpStatus.CONFLICT, "ivia_force_off");
        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    // ── revoke ──────────────────────────────────────────────────

    @Test
    void revokeSoft_inline_disablesOnDemographicDn_auditsUserDisable() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true),
                        presentInlineStatus(false, true));

        service.revoke(DIR_ID, DEMOGRAPHIC_DN, IsvaRevokeMode.SOFT, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        ModifyStep step = (ModifyStep) stepCap.getValue();
        assertThat(step.targetDn()).isEqualTo(DEMOGRAPHIC_DN);
        assertThat(step.mods()).anySatisfy(m ->
                assertThat(m.getAttributeName()).isEqualToIgnoringCase("secAcctValid"));

        assertAudit(AuditAction.USER_DISABLE, Map.of(
                "source", "ivia",
                "ivia_op", "revoke_soft",
                "mode", "inline"));
    }

    @Test
    void revokeHard_linked_deletesSecUserDn_auditsUserDelete() {
        VendorIntegrationIsvaConfig cfg = linkedConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentLinkedStatus(SECUSER_DN, true, true),
                        IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED));

        service.revoke(DIR_ID, DEMOGRAPHIC_DN, IsvaRevokeMode.HARD, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        DeleteStep step = (DeleteStep) stepCap.getValue();
        assertThat(step.targetDn()).isEqualTo(SECUSER_DN);

        assertAudit(AuditAction.USER_DELETE, Map.of(
                "source", "ivia",
                "ivia_op", "revoke_hard",
                "mode", "linked"));
    }

    @Test
    void revokeHard_inline_stripsOverlay_auditsUserDelete() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true),
                        IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));

        service.revoke(DIR_ID, DEMOGRAPHIC_DN, IsvaRevokeMode.HARD, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        ModifyStep step = (ModifyStep) stepCap.getValue();
        assertThat(step.targetDn()).isEqualTo(DEMOGRAPHIC_DN);
        // Every modification is a DELETE — we're stripping the overlay.
        assertThat(step.mods()).allMatch(m ->
                m.getModificationType() == com.unboundid.ldap.sdk.ModificationType.DELETE);
        assertThat(step.mods()).anySatisfy(m -> {
            assertThat(m.getAttributeName()).isEqualToIgnoringCase("objectClass");
            assertThat(m.getValues()).contains("secUser");
        });

        assertAudit(AuditAction.USER_DELETE, Map.of(
                "source", "ivia",
                "ivia_op", "revoke_hard",
                "mode", "inline"));
    }

    @Test
    void revoke_orphan_409_iviaOrphan_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = linkedConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED));

        assertRefusal(() -> service.revoke(DIR_ID, DEMOGRAPHIC_DN, IsvaRevokeMode.SOFT, principal),
                HttpStatus.CONFLICT, "ivia_orphan");
        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    // ── suspend / restore (idempotent) ─────────────────────────

    @Test
    void suspend_happyPath_flipsAcctValid() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true),
                        presentInlineStatus(false, true));

        service.suspend(DIR_ID, DEMOGRAPHIC_DN, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        ModifyStep step = (ModifyStep) stepCap.getValue();
        Modification mod = step.mods().get(0);
        assertThat(mod.getAttributeName()).isEqualToIgnoringCase("secAcctValid");
        assertThat(mod.getValues()[0]).isEqualTo("FALSE");

        assertAudit(AuditAction.USER_DISABLE, Map.of("ivia_op", "suspend"));
    }

    @Test
    void suspend_alreadySuspended_noOp_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        // acctValid=false → already suspended
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(false, true));

        IsvaAccountStatus got = service.suspend(DIR_ID, DEMOGRAPHIC_DN, principal);

        assertThat(got.acctValid()).isFalse();
        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    @Test
    void restore_alreadyActive_noOp_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true));

        service.restore(DIR_ID, DEMOGRAPHIC_DN, principal);

        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    // ── renew ──────────────────────────────────────────────────

    @Test
    void renew_happyPath_extendsValidUntil_auditsValidUntilInDetail() {
        VendorIntegrationIsvaConfig cfg = linkedConfig(true);
        givenDirectoryAndConfig(cfg);
        OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentLinkedStatusWithValidUntil(current),
                        presentLinkedStatusWithValidUntil(current.plusYears(1)));

        OffsetDateTime newValid = current.plusYears(1);
        service.renew(DIR_ID, DEMOGRAPHIC_DN, newValid, principal);

        ArgumentCaptor<Map<String, Object>> detailCap = mapCaptor();
        verify(auditService).record(eq(principal), eq(DIR_ID),
                eq(AuditAction.USER_UPDATE), eq(DEMOGRAPHIC_DN), detailCap.capture());
        assertThat(detailCap.getValue())
                .containsEntry("ivia_op", "renew")
                .containsKey("validUntil");
    }

    @Test
    void renew_earlierThanCurrent_400_iviaRenewNotForward() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC).plusYears(2);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatusWithValidUntil(current));

        assertRefusal(() -> service.renew(DIR_ID, DEMOGRAPHIC_DN,
                        current.minusDays(7), principal),
                HttpStatus.BAD_REQUEST, "ivia_renew_not_forward");
        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    @Test
    void renew_inPast_400_iviaRenewNotForward() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true));

        assertRefusal(() -> service.renew(DIR_ID, DEMOGRAPHIC_DN,
                        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), principal),
                HttpStatus.BAD_REQUEST, "ivia_renew_not_forward");
    }

    @Test
    void renew_tooFarAhead_400_iviaRenewNotForward() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true));

        assertRefusal(() -> service.renew(DIR_ID, DEMOGRAPHIC_DN,
                        OffsetDateTime.now(ZoneOffset.UTC).plusYears(50), principal),
                HttpStatus.BAD_REQUEST, "ivia_renew_not_forward");
    }

    @Test
    void renew_equalsCurrent_noOp_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC).plusYears(1);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatusWithValidUntil(current));

        service.renew(DIR_ID, DEMOGRAPHIC_DN, current, principal);

        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    // ── forceCredentialReset ──────────────────────────────────

    @Test
    void forceCredentialReset_happyPath_flipsPwdValid_auditsPasswordReset() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true),
                        presentInlineStatus(true, false));

        service.forceCredentialReset(DIR_ID, DEMOGRAPHIC_DN, principal);

        ArgumentCaptor<LdapOperationStep> stepCap = ArgumentCaptor.forClass(LdapOperationStep.class);
        verify(planExecutor).execute(any(), stepCap.capture());
        ModifyStep step = (ModifyStep) stepCap.getValue();
        assertThat(step.mods().get(0).getAttributeName()).isEqualToIgnoringCase("secPwdValid");
        assertThat(step.mods().get(0).getValues()[0]).isEqualTo("FALSE");

        assertAudit(AuditAction.PASSWORD_RESET, Map.of("ivia_op", "force_reset"));
    }

    @Test
    void forceCredentialReset_alreadyInvalid_noOp_doesNotWriteOrAudit() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, false));

        service.forceCredentialReset(DIR_ID, DEMOGRAPHIC_DN, principal);

        verifyNoInteractions(planExecutor);
        verifyNoInteractions(auditService);
    }

    // ── TOCTOU translation ───────────────────────────────────

    @Test
    void toctou_noSuchObjectOnRevoke_409_iviaStateChanged() {
        VendorIntegrationIsvaConfig cfg = linkedConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentLinkedStatus(SECUSER_DN, true, true));
        // Concurrent operator deleted the secUser between probe and verb.
        LDAPException ldap = new LDAPException(ResultCode.NO_SUCH_OBJECT, "gone");
        doThrow(new LdapOperationException("plan aborted", ldap))
                .when(planExecutor).execute(any(), any(LdapOperationStep.class));

        assertRefusal(() -> service.revoke(DIR_ID, DEMOGRAPHIC_DN, IsvaRevokeMode.SOFT, principal),
                HttpStatus.CONFLICT, "ivia_state_changed");
        // Pre-flight audit is still suppressed when the write itself fails.
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void toctou_attributeOrValueExistsOnGrant_409_iviaStateChanged() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        givenNotForceOff();
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE));
        when(probe.resolveUid(any(), eq(DEMOGRAPHIC_DN))).thenReturn("alice");
        LDAPException ldap = new LDAPException(ResultCode.ATTRIBUTE_OR_VALUE_EXISTS, "raced");
        doThrow(new LdapOperationException("plan aborted", ldap))
                .when(planExecutor).execute(any(), any(LdapOperationStep.class));

        assertRefusal(() -> service.grant(DIR_ID, DEMOGRAPHIC_DN, principal),
                HttpStatus.CONFLICT, "ivia_state_changed");
    }

    @Test
    void otherLdapErrorBubblesAs_LdapOperationException() {
        VendorIntegrationIsvaConfig cfg = inlineConfig(true);
        givenDirectoryAndConfig(cfg);
        when(probe.probe(any(), eq(cfg), eq(DEMOGRAPHIC_DN)))
                .thenReturn(presentInlineStatus(true, true));
        LDAPException ldap = new LDAPException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS, "denied");
        doThrow(new LdapOperationException("plan aborted", ldap))
                .when(planExecutor).execute(any(), any(LdapOperationStep.class));

        assertThatThrownBy(() -> service.suspend(DIR_ID, DEMOGRAPHIC_DN, principal))
                .isInstanceOf(LdapOperationException.class);
    }

    // ── fixtures / helpers ──────────────────────────────────

    private void givenDirectoryAndConfig(VendorIntegrationIsvaConfig cfg) {
        when(dirRepo.findById(DIR_ID)).thenReturn(Optional.of(buildDir()));
        when(configRepo.findById(DIR_ID)).thenReturn(Optional.of(cfg));
    }

    private void givenDirectoryButNoConfig() {
        when(dirRepo.findById(DIR_ID)).thenReturn(Optional.of(buildDir()));
        when(configRepo.findById(DIR_ID)).thenReturn(Optional.empty());
    }

    private void givenNotForceOff() {
        // resolveProfileForDn returns empty → profileId=null → not exempt.
        when(profileService.resolveProfileForDn(eq(DIR_ID), eq(DEMOGRAPHIC_DN)))
                .thenReturn(Optional.empty());
        when(overrideService.isExemptFromIvia(any())).thenReturn(false);
    }

    private DirectoryConnection buildDir() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(DIR_ID);
        d.setDisplayName("test-dir");
        return d;
    }

    private VendorIntegrationIsvaConfig inlineConfig(boolean enabled) {
        VendorIntegrationIsvaConfig c = new VendorIntegrationIsvaConfig();
        c.setDirectoryConnectionId(DIR_ID);
        c.setEnabled(enabled);
        c.setTopologyMode(IsvaTopologyMode.INLINE);
        c.setSecAuthority("Default");
        c.setDefaultValidUntilYears(100);
        return c;
    }

    private VendorIntegrationIsvaConfig linkedConfig(boolean enabled) {
        VendorIntegrationIsvaConfig c = new VendorIntegrationIsvaConfig();
        c.setDirectoryConnectionId(DIR_ID);
        c.setEnabled(enabled);
        c.setTopologyMode(IsvaTopologyMode.LINKED);
        c.setManagementDitBaseDn(MGMT_BASE);
        c.setSecuserRdnAttribute("secUUID");
        c.setSecAuthority("Default");
        c.setDefaultValidUntilYears(100);
        return c;
    }

    private IsvaAccountStatus presentInlineStatus(boolean acctValid, boolean pwdValid) {
        return IsvaAccountStatus.present(IsvaTopologyMode.INLINE, null,
                acctValid, OffsetDateTime.now(ZoneOffset.UTC).plusYears(1),
                pwdValid, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                "Default");
    }

    private IsvaAccountStatus presentInlineStatusWithValidUntil(OffsetDateTime validUntil) {
        return IsvaAccountStatus.present(IsvaTopologyMode.INLINE, null,
                true, validUntil, true,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), "Default");
    }

    private IsvaAccountStatus presentLinkedStatus(String secUserDn,
                                                  boolean acctValid,
                                                  boolean pwdValid) {
        return IsvaAccountStatus.present(IsvaTopologyMode.LINKED, secUserDn,
                acctValid, OffsetDateTime.now(ZoneOffset.UTC).plusYears(1),
                pwdValid, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                "Default");
    }

    private IsvaAccountStatus presentLinkedStatusWithValidUntil(OffsetDateTime validUntil) {
        return IsvaAccountStatus.present(IsvaTopologyMode.LINKED, SECUSER_DN,
                true, validUntil, true,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), "Default");
    }

    private void assertAudit(AuditAction expectedAction, Map<String, Object> expectedDetailSubset) {
        ArgumentCaptor<Map<String, Object>> detailCap = mapCaptor();
        verify(auditService).record(eq(principal), eq(DIR_ID),
                eq(expectedAction), eq(DEMOGRAPHIC_DN), detailCap.capture());
        Map<String, Object> got = detailCap.getValue();
        assertThat(got).containsAllEntriesOf(expectedDetailSubset);
    }

    private void assertRefusal(Runnable verb, HttpStatus expectedStatus, String expectedCode) {
        assertThatThrownBy(verb::run)
                .isInstanceOf(IsvaAccountRefusalException.class)
                .satisfies(ex -> {
                    IsvaAccountRefusalException r = (IsvaAccountRefusalException) ex;
                    assertThat(r.getStatus()).isEqualTo(expectedStatus);
                    assertThat(r.getCode()).isEqualTo(expectedCode);
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
