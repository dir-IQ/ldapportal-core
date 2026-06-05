// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.config.AppProperties;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.PendingApprovalRepository;
import com.ldapportal.repository.RegistrationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused on the global approvals master switch added to the workflow service.
 * The broad approval flow is exercised elsewhere; these tests pin that the
 * {@code approvalsEnabled} chokepoint short-circuits before any profile lookup
 * or submission when approvals are globally off, and that the existing
 * submit-on-required behaviour is unchanged when on.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceTest {

    @Mock private PendingApprovalRepository approvalRepo;
    @Mock private AccountRepository accountRepo;
    @Mock private RegistrationRequestRepository registrationRepo;
    @Mock private PermissionService permissionService;
    @Mock private ProvisioningProfileService profileService;
    @Mock private LdapOperationService ldapOperationService;
    @Mock private AuditService auditService;
    @Mock private ApprovalNotificationService notificationService;
    @Mock private NotificationService inAppNotificationService;
    @Mock private ApplicationSettingsService settingsService;
    @Mock private ApplicationContext applicationContext;

    private ApprovalWorkflowService service;

    private final UUID dirId = UUID.randomUUID();
    private final String dn = "uid=alice,ou=people,dc=example,dc=com";

    @BeforeEach
    void setUp() {
        service = new ApprovalWorkflowService(
                approvalRepo, accountRepo, registrationRepo, permissionService,
                profileService, ldapOperationService, auditService, notificationService,
                inAppNotificationService, new ObjectMapper(), settingsService,
                new AppProperties(), applicationContext);
    }

    private AuthPrincipal admin() {
        return new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "alice");
    }

    @Test
    void checkAndSubmit_globalApprovalsOff_returnsEmptyAndNeverSubmits() {
        when(settingsService.isApprovalsEnabled()).thenReturn(false);

        Optional<PendingApproval> result = service.checkAndSubmitForApproval(
                dirId, dn, admin(), ApprovalRequestType.USER_CREATE, Map.of("k", "v"));

        assertThat(result).isEmpty();
        // Short-circuits before any profile resolution or persistence.
        verify(profileService, never()).resolveProfileForDn(any(), any());
        verify(approvalRepo, never()).save(any());
    }

    @Test
    void checkAndSubmit_globalApprovalsOn_profileRequires_submits() {
        UUID profileId = UUID.randomUUID();
        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setId(profileId);

        when(settingsService.isApprovalsEnabled()).thenReturn(true);
        when(profileService.resolveProfileForDn(dirId, dn)).thenReturn(Optional.of(profile));
        when(profileService.isApprovalRequired(profileId)).thenReturn(true);
        when(approvalRepo.save(any())).thenAnswer(inv -> {
            PendingApproval pa = inv.getArgument(0);
            pa.setId(UUID.randomUUID());
            return pa;
        });

        Optional<PendingApproval> result = service.checkAndSubmitForApproval(
                dirId, dn, admin(), ApprovalRequestType.USER_CREATE, Map.of("k", "v"));

        assertThat(result).isPresent();
        verify(approvalRepo).save(any());
    }

    @Test
    void predicates_delegateToSettings() {
        when(settingsService.isApprovalsEnabled()).thenReturn(false);
        when(settingsService.isSelfRegistrationApprovalEnabled()).thenReturn(true);

        assertThat(service.approvalsEnabled()).isFalse();
        assertThat(service.selfRegistrationApprovalEnabled()).isTrue();
    }
}
