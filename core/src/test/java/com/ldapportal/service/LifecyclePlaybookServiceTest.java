// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.LifecyclePlaybook;
import com.ldapportal.entity.PlaybookExecution;
import com.ldapportal.entity.PlaybookStep;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.PlaybookExecutionStatus;
import com.ldapportal.entity.enums.PlaybookStepAction;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapGroupService.DirectMembership;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.LifecyclePlaybookRepository;
import com.ldapportal.repository.PlaybookExecutionRepository;
import com.ldapportal.repository.PlaybookStepRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * The REMOVE_ALL_GROUPS step: finds the user's groups from the groups
 * themselves (not memberOf, which a plain read of an OpenLDAP entry never
 * returns), removes each under the attribute that lists the user, and
 * rolls back exactly what it removed.
 */
@ExtendWith(MockitoExtension.class)
class LifecyclePlaybookServiceTest {

    @Mock private LifecyclePlaybookRepository playbookRepo;
    @Mock private PlaybookStepRepository stepRepo;
    @Mock private PlaybookExecutionRepository executionRepo;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private ProvisioningProfileRepository profileRepo;
    @Mock private LdapUserService ldapUserService;
    @Mock private LdapGroupService ldapGroupService;
    @Mock private AuditService auditService;
    @Mock private ApprovalNotificationService notificationService;
    @Mock private ApprovalWorkflowService approvalService;
    @Mock private PermissionService permissionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LifecyclePlaybookService service;

    private static final String USER_DN = "uid=jdoe,ou=people,dc=example,dc=com";
    private static final String STAFF = "cn=staff,ou=groups,dc=example,dc=com";
    private static final String LEGACY = "cn=legacy,ou=groups,dc=example,dc=com";
    private static final String POSIX = "cn=devs,ou=groups,dc=example,dc=com";

    private final UUID dirId = UUID.randomUUID();
    private final UUID playbookId = UUID.randomUUID();
    private final AuthPrincipal principal =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");
    private DirectoryConnection dc;
    private LifecyclePlaybook playbook;

    @BeforeEach
    void setUp() {
        service = new LifecyclePlaybookService(playbookRepo, stepRepo, executionRepo, dirRepo,
                profileRepo, ldapUserService, ldapGroupService, auditService, notificationService,
                approvalService, permissionService, objectMapper);
        dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        playbook = new LifecyclePlaybook();
        playbook.setId(playbookId);
        playbook.setName("offboard");
        playbook.setDirectory(dc);
    }

    private void stubRemoveAllGroupsPlaybook() {
        PlaybookStep step = new PlaybookStep();
        step.setStepOrder(1);
        step.setAction(PlaybookStepAction.REMOVE_ALL_GROUPS);
        given(playbookRepo.findByIdAndDirectoryId(playbookId, dirId)).willReturn(Optional.of(playbook));
        given(dirRepo.findById(dirId)).willReturn(Optional.of(dc));
        given(stepRepo.findAllByPlaybookIdOrderByStepOrderAsc(playbookId)).willReturn(List.of(step));
        given(executionRepo.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(ldapUserService.getUser(dc, USER_DN, "uid"))
                .willReturn(new LdapUser(USER_DN, Map.of("uid", List.of("jdoe"))));
    }

    private static LdapGroup group(String dn) {
        return new LdapGroup(dn, Map.of());
    }

    @Test
    void removeAllGroups_removesEachMembershipUnderTheAttributeThatListsIt() throws Exception {
        stubRemoveAllGroupsPlaybook();
        given(ldapGroupService.findDirectMemberships(dc, USER_DN, "jdoe", "cn")).willReturn(List.of(
                new DirectMembership(group(STAFF), "member", USER_DN),
                new DirectMembership(group(POSIX), "memberUid", "jdoe")));

        var response = service.executeImmediate(dirId, playbookId, USER_DN, principal);

        assertThat(response.status()).isEqualTo("SUCCESS");
        verify(ldapGroupService).removeMember(dc, STAFF, "member", USER_DN);
        verify(ldapGroupService).removeMember(dc, POSIX, "memberUid", "jdoe");
        // The snapshot keeps attribute + value, so a rollback can restore memberUid.
        JsonNode snapshot = objectMapper.readTree(
                objectMapper.readTree(response.stepResults()).get(0).path("previousState").asText());
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(1).path("groupDn").asText()).isEqualTo(POSIX);
        assertThat(snapshot.get(1).path("attribute").asText()).isEqualTo("memberUid");
        assertThat(snapshot.get(1).path("value").asText()).isEqualTo("jdoe");
    }

    @Test
    void removeAllGroups_aMembershipThatCantBeRemoved_failsTheStep() {
        stubRemoveAllGroupsPlaybook();
        given(ldapGroupService.findDirectMemberships(dc, USER_DN, "jdoe", "cn")).willReturn(List.of(
                new DirectMembership(group(STAFF), "member", USER_DN)));
        willThrow(new RuntimeException("insufficient access"))
                .given(ldapGroupService).removeMember(dc, STAFF, "member", USER_DN);

        var response = service.executeImmediate(dirId, playbookId, USER_DN, principal);

        // Offboarding that leaves access behind must not report success.
        assertThat(response.status()).isEqualTo(PlaybookExecutionStatus.PARTIAL.name());
        assertThat(response.stepResults()).contains("FAILED").contains(STAFF);
    }

    @Test
    void rollback_restoresAttributeAwareSnapshots_andLegacyDnOnlySnapshots() throws Exception {
        String snapshot = objectMapper.writeValueAsString(List.of(
                Map.of("groupDn", POSIX, "attribute", "memberUid", "value", "jdoe"),
                LEGACY));
        String stepResults = objectMapper.writeValueAsString(List.of(Map.of(
                "stepOrder", 1, "action", "REMOVE_ALL_GROUPS",
                "status", "SUCCESS", "previousState", snapshot)));
        PlaybookExecution exec = new PlaybookExecution();
        exec.setPlaybook(playbook);
        exec.setTargetDn(USER_DN);
        exec.setStatus(PlaybookExecutionStatus.SUCCESS);
        exec.setStepResults(stepResults);
        UUID execId = UUID.randomUUID();
        given(executionRepo.findById(execId)).willReturn(Optional.of(exec));
        given(dirRepo.findById(dirId)).willReturn(Optional.of(dc));

        service.rollback(execId, principal);

        verify(ldapGroupService).addMember(dc, POSIX, "memberUid", "jdoe");
        verify(ldapGroupService).addMember(dc, LEGACY, "member", USER_DN);
    }
}
