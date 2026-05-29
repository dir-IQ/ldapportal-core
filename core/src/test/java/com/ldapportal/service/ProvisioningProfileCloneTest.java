// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProfileApprovalConfig;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProfileLifecyclePolicy;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.ApproverMode;
import com.ldapportal.entity.enums.ExpiryAction;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProfileApprovalConfigRepository;
import com.ldapportal.repository.ProfileApproverRepository;
import com.ldapportal.repository.ProfileAttributeConfigRepository;
import com.ldapportal.repository.ProfileGroupAssignmentRepository;
import com.ldapportal.repository.ProfileLifecyclePolicyRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Fidelity tests for {@link ProvisioningProfileService#clone}. The original
 * clone path dropped several attribute-config fields, never copied lifecycle
 * or approval config, and saved the profile entity twice. These tests pin
 * the new behavior so the next refactor can't silently regress it.
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningProfileCloneTest {

    @Mock private ProvisioningProfileRepository      profileRepo;
    @Mock private ProfileAttributeConfigRepository   attrConfigRepo;
    @Mock private ProfileGroupAssignmentRepository   groupAssignmentRepo;
    @Mock private ProfileLifecyclePolicyRepository   lifecycleRepo;
    @Mock private ProfileApprovalConfigRepository    approvalConfigRepo;
    @Mock private ProfileApproverRepository          approverRepo;
    @Mock private DirectoryConnectionRepository      dirRepo;
    @Mock private AccountRepository                  accountRepo;
    @Mock private AdminProfileRoleRepository         adminProfileRoleRepo;
    @Mock private com.ldapportal.core.entitlement.UsageLimitService usageLimitService;
    @Mock private PasswordGeneratorService passwordGenerator;
    @Mock private com.ldapportal.ldap.LdapUserService ldapUserService;
    @Mock private com.ldapportal.ldap.LdapGroupService ldapGroupService;
    @Mock private com.ldapportal.ldap.LdapBrowseService ldapBrowseService;
    @Mock private AuditService auditService;

    private ProvisioningProfileService service;

    private DirectoryConnection directory;
    private ProvisioningProfile source;
    private UUID directoryId;
    private UUID sourceId;

    @BeforeEach
    void setUp() {
        service = new ProvisioningProfileService(
                profileRepo, attrConfigRepo, groupAssignmentRepo, lifecycleRepo,
                approvalConfigRepo, approverRepo, dirRepo, accountRepo,
                adminProfileRoleRepo, new ObjectMapper(), usageLimitService,
                passwordGenerator, ldapUserService, ldapGroupService,
                ldapBrowseService, auditService);

        directoryId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        directory = new DirectoryConnection();
        directory.setId(directoryId);
        directory.setDisplayName("dir-1");

        source = new ProvisioningProfile();
        source.setId(sourceId);
        source.setDirectory(directory);
        source.setName("source");
        source.setTargetOuDn("ou=people,dc=example,dc=com");
        source.setObjectClassNames(new ArrayList<>(List.of("inetOrgPerson")));
        source.setRdnAttribute("uid");
        source.setAdditionalProfiles(new HashSet<>());

        given(profileRepo.findByIdAndDirectoryId(sourceId, directoryId))
                .willReturn(Optional.of(source));
        given(profileRepo.existsByDirectoryIdAndName(directoryId, "copy")).willReturn(false);
        given(profileRepo.save(any(ProvisioningProfile.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(sourceId))
                .willReturn(List.of());
        given(groupAssignmentRepo.findAllByProfileIdOrderByDisplayOrderAsc(sourceId))
                .willReturn(List.of());
        // toResponse() at the end re-reads configs/groups on the clone id —
        // which is null in this Mockito-only setup; the call still goes
        // through but returns empty lists by default.
    }

    @Test
    void clone_persistsProfileExactlyOnce() {
        given(lifecycleRepo.findByProfileId(sourceId)).willReturn(Optional.empty());
        given(approvalConfigRepo.findByProfileId(sourceId)).willReturn(Optional.empty());

        service.clone(directoryId, sourceId, "copy");

        // Previously the clone path saved twice — once empty, once with
        // additionalProfiles attached. Verify the consolidated single save.
        verify(profileRepo, times(1)).save(any(ProvisioningProfile.class));
    }

    @Test
    void clone_copiesAllAttributeConfigFieldsIncludingPreviouslyDropped() {
        ProfileAttributeConfig src = new ProfileAttributeConfig();
        src.setAttributeName("mail");
        src.setCustomLabel("Email address");
        src.setInputType(InputType.TEXT);
        src.setRequiredOnCreate(true);
        src.setEditableOnCreate(true);
        src.setEditableOnUpdate(true);
        src.setSelfServiceEdit(true);
        src.setSelfRegistrationEdit(true);
        src.setDefaultValue("");
        src.setComputedExpression(null);
        src.setValidationRegex("^.+@.+$");
        src.setValidationMessage("must be an email");
        src.setAllowedValues(null);
        src.setMinLength(3);
        src.setMaxLength(254);
        src.setSectionName("Contact");
        src.setColumnSpan(6);
        src.setDisplayOrder(2);
        src.setHidden(false);
        src.setRegistrationSectionName("Registration");
        src.setRegistrationColumnSpan(4);
        src.setRegistrationDisplayOrder(1);
        src.setSelfServiceSectionName("Profile");
        src.setSelfServiceColumnSpan(3);
        src.setSelfServiceDisplayOrder(5);

        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(sourceId))
                .willReturn(List.of(src));
        given(lifecycleRepo.findByProfileId(sourceId)).willReturn(Optional.empty());
        given(approvalConfigRepo.findByProfileId(sourceId)).willReturn(Optional.empty());

        service.clone(directoryId, sourceId, "copy");

        ArgumentCaptor<ProfileAttributeConfig> captor =
                ArgumentCaptor.forClass(ProfileAttributeConfig.class);
        verify(attrConfigRepo).save(captor.capture());
        ProfileAttributeConfig saved = captor.getValue();

        // Spot-check the fields that the old clone path dropped — the
        // self-registration + per-context layout columns.
        assertThat(saved.isSelfRegistrationEdit()).isTrue();
        assertThat(saved.getRegistrationSectionName()).isEqualTo("Registration");
        assertThat(saved.getRegistrationColumnSpan()).isEqualTo(4);
        assertThat(saved.getRegistrationDisplayOrder()).isEqualTo(1);
        assertThat(saved.getSelfServiceSectionName()).isEqualTo("Profile");
        assertThat(saved.getSelfServiceColumnSpan()).isEqualTo(3);
        assertThat(saved.getSelfServiceDisplayOrder()).isEqualTo(5);
        // And that the previously-copied fields still come over.
        assertThat(saved.getValidationRegex()).isEqualTo("^.+@.+$");
        assertThat(saved.getInputType()).isEqualTo(InputType.TEXT);
    }

    @Test
    void clone_copiesLifecyclePolicyWhenPresent() {
        ProfileLifecyclePolicy src = new ProfileLifecyclePolicy();
        src.setExpiresAfterDays(90);
        src.setMaxRenewals(3);
        src.setRenewalDays(30);
        src.setOnExpiryAction(ExpiryAction.MOVE);
        src.setOnExpiryMoveDn("ou=expired,dc=example,dc=com");
        src.setOnExpiryRemoveGroups(false);
        src.setOnExpiryNotify(true);
        src.setWarningDaysBefore(7);

        given(lifecycleRepo.findByProfileId(sourceId)).willReturn(Optional.of(src));
        given(approvalConfigRepo.findByProfileId(sourceId)).willReturn(Optional.empty());

        service.clone(directoryId, sourceId, "copy");

        ArgumentCaptor<ProfileLifecyclePolicy> captor =
                ArgumentCaptor.forClass(ProfileLifecyclePolicy.class);
        verify(lifecycleRepo).save(captor.capture());
        ProfileLifecyclePolicy saved = captor.getValue();

        assertThat(saved.getExpiresAfterDays()).isEqualTo(90);
        assertThat(saved.getOnExpiryAction()).isEqualTo(ExpiryAction.MOVE);
        assertThat(saved.getOnExpiryMoveDn()).isEqualTo("ou=expired,dc=example,dc=com");
        assertThat(saved.isOnExpiryRemoveGroups()).isFalse();
        assertThat(saved.getWarningDaysBefore()).isEqualTo(7);
    }

    @Test
    void clone_copiesApprovalConfigButNotApprovers() {
        ProfileApprovalConfig src = new ProfileApprovalConfig();
        src.setRequireApproval(true);
        src.setApproverMode(ApproverMode.LDAP_GROUP);
        src.setApproverGroupDn("cn=approvers,dc=example,dc=com");
        src.setAutoEscalateDays(5);

        given(lifecycleRepo.findByProfileId(sourceId)).willReturn(Optional.empty());
        given(approvalConfigRepo.findByProfileId(sourceId)).willReturn(Optional.of(src));

        service.clone(directoryId, sourceId, "copy");

        ArgumentCaptor<ProfileApprovalConfig> captor =
                ArgumentCaptor.forClass(ProfileApprovalConfig.class);
        verify(approvalConfigRepo).save(captor.capture());
        ProfileApprovalConfig saved = captor.getValue();

        assertThat(saved.isRequireApproval()).isTrue();
        assertThat(saved.getApproverMode()).isEqualTo(ApproverMode.LDAP_GROUP);
        assertThat(saved.getApproverGroupDn()).isEqualTo("cn=approvers,dc=example,dc=com");
        assertThat(saved.getAutoEscalateDays()).isEqualTo(5);

        // Critical: approvers are people, not config. Don't copy them.
        // Copying would silently grant the source's approver list approve
        // power over the new (initially disabled) profile.
        verify(approverRepo, never()).save(any());
    }
}
