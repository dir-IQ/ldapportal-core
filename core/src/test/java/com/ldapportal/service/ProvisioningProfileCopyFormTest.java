// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.exception.ResourceNotFoundException;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ProvisioningProfileService#copyForm}: one profile's user form
 * replacing other profiles' (dev / test / prod sharing a definition).
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningProfileCopyFormTest {

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
    @Mock private com.ldapportal.ldap.LdapSchemaService ldapSchemaService;
    @Mock private AuditService auditService;

    private ProvisioningProfileService service;

    private final UUID prodDirId = UUID.randomUUID();
    private final UUID testDirId = UUID.randomUUID();
    private ProvisioningProfile source;
    private ProvisioningProfile target;

    @BeforeEach
    void setUp() {
        service = new ProvisioningProfileService(
                profileRepo, attrConfigRepo, groupAssignmentRepo, lifecycleRepo,
                approvalConfigRepo, approverRepo, dirRepo, accountRepo,
                adminProfileRoleRepo, new ObjectMapper(), usageLimitService,
                passwordGenerator, ldapUserService, ldapGroupService,
                ldapBrowseService, ldapSchemaService, auditService);

        source = profile(prodDirId, "prod", "Employees");
        source.setDnColumnSpan(12);
        source.setDnSectionName("Identity");
        source.setDnDisplayOrder(3);

        target = profile(testDirId, "test", "Employees (test)");
        target.setTargetUserDn("ou=people,dc=test,dc=corp");
        target.setObjectClassNames(new ArrayList<>(List.of("inetOrgPerson", "testExtra")));
        target.setShowDnField(true);
        target.setDnTemplate("uid={uid},ou=people,dc=test,dc=corp");

        given(profileRepo.findByIdAndDirectoryId(source.getId(), prodDirId)).willReturn(Optional.of(source));
        lenient().when(profileRepo.findById(target.getId())).thenReturn(Optional.of(target));
        lenient().when(profileRepo.save(any(ProvisioningProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(source.getId()))
                .thenReturn(List.of(uid(), mail()));
    }

    private static ProvisioningProfile profile(UUID dirId, String dirName, String name) {
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(dirId);
        dir.setDisplayName(dirName);
        ProvisioningProfile p = new ProvisioningProfile();
        p.setId(UUID.randomUUID());
        p.setDirectory(dir);
        p.setName(name);
        p.setTargetUserDn("ou=people,dc=" + dirName + ",dc=corp");
        p.setObjectClassNames(new ArrayList<>(List.of("inetOrgPerson")));
        p.setRdnAttribute("uid");
        p.setAdditionalProfiles(new HashSet<>());
        return p;
    }

    private static ProfileAttributeConfig uid() {
        ProfileAttributeConfig c = new ProfileAttributeConfig();
        c.setAttributeName("uid");
        c.setCustomLabel("User ID");
        c.setInputType(InputType.TEXT);
        c.setRequiredOnCreate(true);
        c.setEditableOnCreate(true);
        c.setEditableOnUpdate(false);
        c.setSectionName("Identity");
        c.setColumnSpan(6);
        c.setDisplayOrder(0);
        return c;
    }

    /** Every optional column set, so a dropped field shows up as a mismatch. */
    private static ProfileAttributeConfig mail() {
        ProfileAttributeConfig c = new ProfileAttributeConfig();
        c.setAttributeName("mail");
        c.setCustomLabel("Email address");
        c.setInputType(InputType.TEXT);
        c.setRequiredOnCreate(true);
        c.setEditableOnCreate(true);
        c.setEditableOnUpdate(true);
        c.setSelfServiceEdit(true);
        c.setSelfRegistrationEdit(true);
        c.setDefaultValue("{uid}@corp.example");
        c.setComputedExpression("{uid}@corp.example");
        c.setValidationRegex("^.+@.+$");
        c.setValidationMessage("must be an email");
        c.setAllowedValues("a,b");
        c.setMinLength(3);
        c.setMaxLength(254);
        c.setSectionName("Contact");
        c.setColumnSpan(4);
        c.setDisplayOrder(1);
        c.setHidden(false);
        c.setRegistrationSectionName("Reg");
        c.setRegistrationColumnSpan(5);
        c.setRegistrationDisplayOrder(7);
        c.setSelfServiceSectionName("Me");
        c.setSelfServiceColumnSpan(8);
        c.setSelfServiceDisplayOrder(9);
        return c;
    }

    private List<ProfileAttributeConfig> savedConfigs(int expected) {
        ArgumentCaptor<ProfileAttributeConfig> captor = ArgumentCaptor.forClass(ProfileAttributeConfig.class);
        verify(attrConfigRepo, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void replacesTheTargetsFormWithEveryFieldOfTheSource_acrossDirectories() {
        service.copyForm(prodDirId, source.getId(), List.of(target.getId()), null);

        verify(attrConfigRepo).deleteAllByProfileId(target.getId());
        List<ProfileAttributeConfig> saved = savedConfigs(2);
        assertThat(saved).allSatisfy(c -> assertThat(c.getProfile()).isSameAs(target));
        assertThat(saved.get(0).getAttributeName()).isEqualTo("uid");
        assertThat(saved.get(1))
                .usingRecursiveComparison()
                .ignoringFields("id", "profile", "displayOrder")
                .isEqualTo(mail());
        assertThat(saved.get(1).getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void copiesTheDnFieldLayout_butKeepsTheTargetsOwnSettings() {
        service.copyForm(prodDirId, source.getId(), List.of(target.getId()), null);

        assertThat(target.getDnColumnSpan()).isEqualTo(12);
        assertThat(target.getDnSectionName()).isEqualTo("Identity");
        assertThat(target.getDnDisplayOrder()).isEqualTo(3);
        // The target's own DN mode, OU, object classes and name are untouched.
        assertThat(target.isShowDnField()).isTrue();
        assertThat(target.getDnTemplate()).isEqualTo("uid={uid},ou=people,dc=test,dc=corp");
        assertThat(target.getTargetUserDn()).isEqualTo("ou=people,dc=test,dc=corp");
        assertThat(target.getObjectClassNames()).containsExactly("inetOrgPerson", "testExtra");
        assertThat(target.getName()).isEqualTo("Employees (test)");
        assertThat(target.getDirectory().getId()).isEqualTo(testDirId);
    }

    @Test
    void auditsEachTargetAsAProfileUpdateNamingTheSource() {
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");

        service.copyForm(prodDirId, source.getId(), List.of(target.getId()), admin);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(admin), eq(testDirId), eq(AuditAction.PROFILE_UPDATE), isNull(),
                detail.capture());
        assertThat(detail.getValue())
                .containsEntry("operation", "copy-form")
                .containsEntry("profileId", target.getId())
                .containsEntry("sourceProfileId", source.getId())
                .containsEntry("sourceDirectoryId", prodDirId);
    }

    @Test
    void refusesTheSourceAsItsOwnTarget() {
        assertThatThrownBy(() -> service.copyForm(prodDirId, source.getId(),
                List.of(target.getId(), source.getId()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
        verify(attrConfigRepo, never()).deleteAllByProfileId(any());
    }

    @Test
    void anUnknownTargetIsNotFound() {
        UUID missing = UUID.randomUUID();
        given(profileRepo.findById(missing)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.copyForm(prodDirId, source.getId(), List.of(missing), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDuplicatedTargetIsCopiedOnce() {
        service.copyForm(prodDirId, source.getId(), List.of(target.getId(), target.getId()), null);

        verify(attrConfigRepo, times(1)).deleteAllByProfileId(target.getId());
        savedConfigs(2);
    }

    // The copied form is checked against the target's own settings, like a
    // manual save: here the target names entries on cn, which the form lacks.
    @Test
    void aFormTheTargetCantUse_isRejectedNamingTheTarget() {
        target.setShowDnField(false);
        target.setRdnAttribute("cn");

        assertThatThrownBy(() -> service.copyForm(prodDirId, source.getId(), List.of(target.getId()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Employees (test)")
                .hasMessageContaining("test")
                .hasMessageContaining("'cn'");
        verify(auditService, never()).record(any(), any(), any(), any(), anyMap());
    }
}
