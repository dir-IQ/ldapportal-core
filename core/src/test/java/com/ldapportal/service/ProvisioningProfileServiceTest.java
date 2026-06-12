// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.profile.ApprovalConfigRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.ProfileApprovalConfig;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.ApproverMode;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Focused unit tests for the validation changes on
 * {@link ProvisioningProfileService}:
 * <ul>
 *   <li>Approval config coherence (LDAP_GROUP needs a group DN; DATABASE clears the stored DN).</li>
 *   <li>Approver role enforcement (only ADMIN/SUPERADMIN accounts can be approvers).</li>
 *   <li>Attribute regex validation: input length cap and defensive handling of
 *       malformed stored patterns.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningProfileServiceTest {

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

    private final UUID profileId = UUID.randomUUID();
    private ProvisioningProfile profile;

    @BeforeEach
    void setUp() {
        service = new ProvisioningProfileService(
                profileRepo, attrConfigRepo, groupAssignmentRepo, lifecycleRepo,
                approvalConfigRepo, approverRepo, dirRepo, accountRepo,
                adminProfileRoleRepo, new ObjectMapper(), usageLimitService,
                passwordGenerator, ldapUserService, ldapGroupService,
                ldapBrowseService, ldapSchemaService, auditService);

        profile = new ProvisioningProfile();
        profile.setId(profileId);
        profile.setName("testers");
    }

    // ── Approval config coherence (#14) ──────────────────────────────────────

    @Test
    void setApprovalConfig_ldapGroupModeWithoutGroupDn_rejected() {
        given(profileRepo.findById(profileId)).willReturn(Optional.of(profile));
        given(approvalConfigRepo.findByProfileId(profileId)).willReturn(Optional.empty());

        var req = new ApprovalConfigRequest(true, ApproverMode.LDAP_GROUP, "  ", null, null);

        assertThatThrownBy(() -> service.setApprovalConfig(profileId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approver group DN");
    }

    @Test
    void setApprovalConfig_databaseModeClearsStoredGroupDn() {
        given(profileRepo.findById(profileId)).willReturn(Optional.of(profile));
        ProfileApprovalConfig existing = new ProfileApprovalConfig();
        existing.setProfile(profile);
        existing.setApproverMode(ApproverMode.LDAP_GROUP);
        existing.setApproverGroupDn("cn=approvers,ou=groups,dc=example,dc=com");
        given(approvalConfigRepo.findByProfileId(profileId)).willReturn(Optional.of(existing));
        given(approvalConfigRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        // Caller passes a DATABASE config; a leftover groupDn in the request
        // body must NOT persist — the stored row must reflect the mode.
        var req = new ApprovalConfigRequest(true, ApproverMode.DATABASE,
                "cn=ignored,dc=example,dc=com", null, null);

        var response = service.setApprovalConfig(profileId, req);
        assertThat(response.approverMode()).isEqualTo(ApproverMode.DATABASE);
        assertThat(response.approverGroupDn()).isNull();
        assertThat(existing.getApproverGroupDn()).isNull();
    }

    @Test
    void setApprovalConfig_requireApprovalFalse_skipsGroupDnCheck() {
        given(profileRepo.findById(profileId)).willReturn(Optional.of(profile));
        given(approvalConfigRepo.findByProfileId(profileId)).willReturn(Optional.empty());
        given(approvalConfigRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        // requireApproval=false with LDAP_GROUP and no group DN is benign:
        // the config isn't active, so demanding a group DN would be theater.
        var req = new ApprovalConfigRequest(false, ApproverMode.LDAP_GROUP, null, null, null);

        var response = service.setApprovalConfig(profileId, req);
        assertThat(response.requireApproval()).isFalse();
    }

    // ── Approver role enforcement (#13) ──────────────────────────────────────

    @Test
    void setApprovers_nonAdminAccount_rejected() {
        given(profileRepo.findById(profileId)).willReturn(Optional.of(profile));

        UUID accountId = UUID.randomUUID();
        Account selfServiceUser = new Account();
        selfServiceUser.setId(accountId);
        selfServiceUser.setUsername("bob");
        // Account.role==null is the "non-admin" case (Account also models
        // non-admin principals like LDAP-bound self-service users).
        selfServiceUser.setRole(null);
        given(accountRepo.findById(accountId)).willReturn(Optional.of(selfServiceUser));

        assertThatThrownBy(() -> service.setApprovers(profileId, List.of(accountId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an admin");
    }

    // ── Regex input cap and defensive handling (#11) ────────────────────────

    @Test
    void validateAttributes_inputOverCap_rejected() {
        ProfileAttributeConfig cfg = new ProfileAttributeConfig();
        cfg.setAttributeName("note");
        cfg.setValidationRegex(".*");
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(cfg));

        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("note", List.of("x".repeat(8192)));

        assertThatThrownBy(() -> service.validateAttributes(profileId, attrs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit for regex-validated fields");
    }

    @Test
    void validateAttributes_malformedStoredRegex_rejectedNotCrashed() {
        ProfileAttributeConfig cfg = new ProfileAttributeConfig();
        cfg.setAttributeName("note");
        // A pre-existing bad pattern in the DB (e.g. predating the
        // save-time validation) must not 500.
        cfg.setValidationRegex("[unterminated");
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(cfg));

        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("note", new ArrayList<>(List.of("hello")));

        assertThatThrownBy(() -> service.validateAttributes(profileId, attrs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation pattern is invalid");
    }

    // ── validateModification (update path: edit-gating + value constraints) ──

    @Test
    void validateModification_enforcesValueConstraints() {
        ProfileAttributeConfig cfg = new ProfileAttributeConfig();
        cfg.setAttributeName("mail");
        cfg.setValidationRegex("^[^@]+@[^@]+$");
        cfg.setValidationMessage("must be an email");
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(cfg));

        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("mail", List.of("not-an-email"));

        assertThatThrownBy(() -> service.validateModification(profileId, List.of("mail"), attrs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an email");
    }

    @Test
    void validateModification_doesNotEnforceRequiredOnCreate() {
        ProfileAttributeConfig required = new ProfileAttributeConfig();
        required.setAttributeName("uid");
        required.setRequiredOnCreate(true);
        ProfileAttributeConfig other = new ProfileAttributeConfig();
        other.setAttributeName("displayName");
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(required, other));

        // The update touches only displayName; the required uid is absent and
        // must NOT trigger a missing-required error on the modify path.
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("displayName", List.of("New Name"));

        assertThatCode(() -> service.validateModification(profileId, List.of("displayName"), attrs))
                .doesNotThrowAnyException();
    }

    @Test
    void validateModification_rejectsNonEditableAttribute() {
        ProfileAttributeConfig cfg = new ProfileAttributeConfig();
        cfg.setAttributeName("employeeNumber");
        cfg.setEditableOnUpdate(false);
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(cfg));

        assertThatThrownBy(() -> service.validateModification(
                profileId, List.of("employeeNumber"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not editable on update");
    }

    @Test
    void validateModification_rejectsHiddenAttribute() {
        ProfileAttributeConfig cfg = new ProfileAttributeConfig();
        cfg.setAttributeName("internalId");
        cfg.setHidden(true);
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(cfg));

        // Case-insensitive match against the modified attribute name.
        assertThatThrownBy(() -> service.validateModification(
                profileId, List.of("INTERNALID"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not editable");
    }

    @Test
    void validateModification_allowsEditableComputedAndUnconfigured() {
        ProfileAttributeConfig editable = new ProfileAttributeConfig();
        editable.setAttributeName("displayName"); // editableOnUpdate defaults to true

        ProfileAttributeConfig computed = new ProfileAttributeConfig();
        computed.setAttributeName("cn");
        computed.setEditableOnUpdate(false);
        computed.setComputedExpression("${givenName}+\" \"+${sn}"); // system-set, exempt

        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profileId))
                .willReturn(List.of(editable, computed));

        // displayName (editable), cn (computed → exempt), mail (no config) — all OK.
        assertThatCode(() -> service.validateModification(
                profileId, List.of("displayName", "cn", "mail"), Map.of()))
                .doesNotThrowAnyException();
    }

    // ── applyGeneratedPassword (server-side password generation) ──────────────

    @Test
    void applyGeneratedPassword_generatesAndInjectsForGeneratedDisposition() {
        profile.setPasswordDisposition(
                com.ldapportal.entity.enums.PasswordDisposition.GENERATED_DISCARDED);
        given(passwordGenerator.generate(profile)).willReturn("Zx9!generated");
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("cn", List.of("Jane"));

        String returned = service.applyGeneratedPassword(profile, attrs);

        assertThat(returned).isEqualTo("Zx9!generated");
        assertThat(attrs.get("userPassword")).containsExactly("Zx9!generated");
    }

    @Test
    void applyGeneratedPassword_noOpForOperatorEntered() {
        // Default disposition is OPERATOR_ENTERED.
        Map<String, List<String>> attrs = new HashMap<>();

        String returned = service.applyGeneratedPassword(profile, attrs);

        assertThat(returned).isNull();
        assertThat(attrs).doesNotContainKey("userPassword");
        org.mockito.Mockito.verifyNoInteractions(passwordGenerator);
    }

    @Test
    void applyGeneratedPassword_doesNotOverwriteSuppliedPassword() {
        profile.setPasswordDisposition(
                com.ldapportal.entity.enums.PasswordDisposition.GENERATED_DELIVERED);
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("userPassword", List.of("operator-typed"));

        String returned = service.applyGeneratedPassword(profile, attrs);

        assertThat(returned).isNull();
        assertThat(attrs.get("userPassword")).containsExactly("operator-typed");
        org.mockito.Mockito.verifyNoInteractions(passwordGenerator);
    }

    // ── Seed defaults vs live schema ─────────────────────────────────────────

    private com.ldapportal.entity.DirectoryConnection seedDirectory(UUID dirId) {
        var dir = new com.ldapportal.entity.DirectoryConnection();
        dir.setId(dirId);
        dir.setDirectoryType(com.ldapportal.entity.enums.DirectoryType.GENERIC);
        profile.setDirectory(dir);
        profile.setObjectClassNames(new ArrayList<>(List.of("inetOrgPerson")));
        given(profileRepo.findByIdAndDirectoryId(profileId, dirId)).willReturn(Optional.of(profile));
        given(attrConfigRepo.countByProfileId(profileId)).willReturn(0L);
        return dir;
    }

    @Test
    void seedAttributeDefaults_filtersRowsNotPermittedByLiveSchema() {
        UUID dirId = UUID.randomUUID();
        var dir = seedDirectory(dirId);
        // Standard inetOrgPerson chain: everything in the curated seed except
        // 'c' (countryName), which the chain does not permit.
        java.util.Set<String> required = java.util.Set.of("cn", "sn", "objectClass");
        java.util.Set<String> optional = java.util.Set.of(
                "uid", "givenName", "displayName", "initials", "employeeNumber",
                "employeeType", "mail", "telephoneNumber", "mobile", "pager",
                "facsimileTelephoneNumber", "homePhone", "postalAddress", "street",
                "l", "st", "postalCode", "title", "ou", "o", "departmentNumber",
                "manager", "description", "userPassword");
        given(ldapSchemaService.getAttributesForObjectClasses(dir, List.of("inetOrgPerson")))
                .willReturn(new com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes(
                        "inetOrgPerson", null, required, optional));

        service.seedAttributeDefaults(dirId, profileId, "inetOrgPerson", null);

        var captor = org.mockito.ArgumentCaptor.forClass(ProfileAttributeConfig.class);
        org.mockito.Mockito.verify(attrConfigRepo, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        List<String> saved = captor.getAllValues().stream()
                .map(ProfileAttributeConfig::getAttributeName).toList();
        assertThat(saved).contains("cn", "l", "st", "postalCode");
        assertThat(saved).doesNotContain("c");
    }

    @Test
    void seedAttributeDefaults_seedsUnfilteredWhenSchemaLookupFails() {
        UUID dirId = UUID.randomUUID();
        var dir = seedDirectory(dirId);
        given(ldapSchemaService.getAttributesForObjectClasses(dir, List.of("inetOrgPerson")))
                .willThrow(new RuntimeException("directory unreachable"));

        service.seedAttributeDefaults(dirId, profileId, "inetOrgPerson", null);

        var captor = org.mockito.ArgumentCaptor.forClass(ProfileAttributeConfig.class);
        org.mockito.Mockito.verify(attrConfigRepo, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        // Graceful fallback: schema unknown must mean "skip filtering",
        // never "nothing permitted".
        assertThat(captor.getAllValues().stream()
                .map(ProfileAttributeConfig::getAttributeName).toList()).contains("c", "cn");
    }
}
