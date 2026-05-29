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
                ldapBrowseService, auditService);

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
}
