// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.governance.MembershipGate;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.core.provisioning.ProvisioningRefusedException;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.dto.ldap.MoveUserRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.dto.csv.BulkImportRequest;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class LdapOperationServiceTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private PermissionService             permissionService;
    @Mock private LdapBrowseService             browseService;
    @Mock private LdapUserService               userService;
    @Mock private LdapGroupService              groupService;
    @Mock private LdapSchemaService             schemaService;
    @Mock private AuditService                  auditService;
    @Mock private BulkUserService               bulkUserService;
    @Mock private BulkGroupService              bulkGroupService;
    @Mock private CsvMappingTemplateService     csvTemplateService;
    @Mock private MembershipGate                membershipGate;

    private LdapOperationService service;

    private final UUID dirId   = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Profile-aware paths (bulk import + delete cleanup) are opt-in
        // via the ObjectProvider — these unit tests exercise the
        // no-profile-context behaviour by returning null.
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> nullProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.lenient().when(nullProvider.getIfAvailable()).thenReturn(null);
        service = new LdapOperationService(
                dirRepo, permissionService, browseService, userService, groupService,
                schemaService, auditService, bulkUserService, bulkGroupService, csvTemplateService,
                membershipGate, nullProvider, nullApprovalProvider());
    }

    // ── Directory loading ─────────────────────────────────────────────────────

    @Test
    void searchUsers_directoryNotFound_throws() {
        when(dirRepo.findById(dirId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.searchUsers(dirId, adminPrincipal(), null, null, 100, new String[0]))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void searchUsers_disabledDirectory_throws() {
        DirectoryConnection dc = enabledDir(false);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        assertThatThrownBy(() -> service.searchUsers(dirId, adminPrincipal(), null, null, 100, new String[0]))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void searchUsers_superadmin_noTenantScope() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        // Superadmin path: resolveSearchBaseDns returns the requested base
        // (null here) unchanged in a single-element list.
        when(permissionService.resolveSearchBaseDns(any(), eq(dirId), any()))
                .thenReturn(java.util.Collections.singletonList(null));
        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class))).thenReturn(List.of());

        List<LdapEntryResponse> result = service.searchUsers(dirId, superadminPrincipal(),
                "(cn=*)", null, 100, new String[0]);

        assertThat(result).isEmpty();
    }

    // ── User operations ───────────────────────────────────────────────────────

    @Test
    void deleteUser_callsUserService() {
        String dn = "cn=Bob,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        service.deleteUser(dirId, adminPrincipal(), dn);

        verify(userService).deleteUser(dc, dn, null);
    }

    @Test
    void updateUser_convertsModificationsToUnboundId() {
        String dn = "cn=Dave,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        LdapUser user = new LdapUser(dn, Map.of("mail", List.of("d@example.com")));
        when(userService.getUser(dc, dn, "*", "modifyTimestamp")).thenReturn(user);

        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("dave@example.com"))));

        LdapEntryResponse resp = service.updateUser(dirId, adminPrincipal(), dn, req);

        verify(userService).updateUser(eq(dc), eq(dn), any());
        assertThat(resp.dn()).isEqualTo(dn);
    }

    // ── If-Unmodified-Since-LDAP precondition (inline edit, Phase 1.5) ────────

    @Test
    void updateUser_preconditionMismatch_throws412AndSkipsWrite() {
        String dn = "cn=Dave,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(userService.getUser(dc, dn, "modifyTimestamp")).thenReturn(
                new LdapUser(dn, Map.of("modifytimestamp", List.of("20260612200000Z"))));

        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("dave@example.com"))));

        assertThatThrownBy(() -> service.updateUser(
                        dirId, adminPrincipal(), dn, req, "20260612180000Z"))
                .isInstanceOf(com.ldapportal.exception.PreconditionFailedException.class)
                .hasMessageContaining("changed since it was loaded");
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void updateUser_preconditionMatch_proceeds() {
        String dn = "cn=Dave,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(userService.getUser(dc, dn, "modifyTimestamp")).thenReturn(
                new LdapUser(dn, Map.of("modifytimestamp", List.of("20260612200000Z"))));
        when(userService.getUser(dc, dn, "*", "modifyTimestamp")).thenReturn(
                new LdapUser(dn, Map.of("mail", List.of("dave@example.com"))));

        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("dave@example.com"))));

        service.updateUser(dirId, adminPrincipal(), dn, req, "20260612200000Z");

        verify(userService).updateUser(eq(dc), eq(dn), any());
    }

    @Test
    void updateUser_preconditionWithoutServerTimestamp_degradesToUnconditional() {
        // A directory that withholds modifyTimestamp must not block every
        // save — the check degrades to last-write-wins.
        String dn = "cn=Dave,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(userService.getUser(dc, dn, "modifyTimestamp")).thenReturn(
                new LdapUser(dn, Map.of()));
        when(userService.getUser(dc, dn, "*", "modifyTimestamp")).thenReturn(
                new LdapUser(dn, Map.of("mail", List.of("dave@example.com"))));

        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("dave@example.com"))));

        service.updateUser(dirId, adminPrincipal(), dn, req, "20260612180000Z");

        verify(userService).updateUser(eq(dc), eq(dn), any());
    }

    @Test
    void createUser_malformedDn_throwsAndSkipsWrite() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        CreateEntryRequest req = new CreateEntryRequest(
                "not a valid dn", Map.of("cn", List.of("Bob")));

        assertThatThrownBy(() -> service.createUser(dirId, adminPrincipal(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DN");

        verify(userService, never()).createUser(any(), anyString(), any(), any());
    }

    @Test
    void moveUser_malformedNewParentDn_throwsAndSkipsWrite() {
        String dn = "cn=Bob,ou=Users,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        assertThatThrownBy(() -> service.moveUser(dirId, adminPrincipal(), dn,
                new MoveUserRequest("not a dn")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DN");

        verify(userService, never()).moveUser(any(), anyString(), anyString());
    }

    @Test
    void createUser_profileValidationFailure_throwsAndSkipsWrite() {
        ProvisioningProfileService ps = mock(ProvisioningProfileService.class);
        UUID profileId = UUID.randomUUID();
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        doThrow(new IllegalArgumentException("Attribute [mail] is required"))
                .when(ps).validateAttributes(eq(profileId), any());

        LdapOperationService svc = serviceWithProfile(ps);
        CreateEntryRequest req = new CreateEntryRequest(
                "uid=jsmith,ou=people,dc=example,dc=com", Map.of("cn", List.of("J")));

        assertThatThrownBy(() -> svc.createUser(dirId, adminPrincipal(), req, profileId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is required");

        verify(userService, never()).createUser(any(), anyString(), any(), any());
    }

    @Test
    void createUser_malformedDnValuedAttribute_throwsAndSkipsWrite() {
        // The DN itself is valid; a well-known DN-valued attribute (manager)
        // carries a malformed value, which the syntax layer must reject
        // before the write — even with no profile in context.
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        CreateEntryRequest req = new CreateEntryRequest(
                "uid=jsmith,ou=people,dc=example,dc=com",
                Map.of("cn", List.of("J"), "manager", List.of("not a dn")));

        assertThatThrownBy(() -> service.createUser(dirId, adminPrincipal(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manager");

        verify(userService, never()).createUser(any(), anyString(), any(), any());
    }

    @Test
    void createUser_mergesMultiValuedRdnNamingValuesIntoAttributes() {
        // o=0001+cn=Sanjay Mishra is a multi-valued RDN: both AVAs must land
        // in the attribute map or non-AD directories reject the add with a
        // naming violation. The merge runs server-side so direct API and bulk
        // callers get it too, not just the web form.
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String dn = "o=0001+cn=Sanjay Mishra,ou=People,dc=example,dc=com";
        when(userService.getUser(dc, dn)).thenReturn(new LdapUser(dn, Map.of()));

        service.createUser(dirId, adminPrincipal(),
                new CreateEntryRequest(dn, Map.of("mail", List.of("sm@example.com"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> attrs =
                ArgumentCaptor.forClass((Class<Map<String, List<String>>>) (Class<?>) Map.class);
        verify(userService).createUser(eq(dc), eq(dn), attrs.capture(), isNull());
        assertThat(attrs.getValue())
                .containsEntry("o", List.of("0001"))
                .containsEntry("cn", List.of("Sanjay Mishra"))
                .containsEntry("mail", List.of("sm@example.com"));
    }

    @Test
    void updateUser_malformedMailAttribute_throwsAndSkipsWrite() {
        String dn = "uid=jsmith,ou=people,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("not-an-email"))));

        assertThatThrownBy(() -> service.updateUser(dirId, adminPrincipal(), dn, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail");

        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void createGroup_malformedMemberDn_throwsAndSkipsWrite() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        CreateEntryRequest req = new CreateEntryRequest(
                "cn=Staff,ou=groups,dc=example,dc=com",
                Map.of("cn", List.of("Staff"), "member", List.of("not a dn")));

        assertThatThrownBy(() -> service.createGroup(dirId, adminPrincipal(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member");

        verify(groupService, never()).createGroup(any(), anyString(), any());
    }

    @Test
    void updateUser_nonEditableAttribute_throwsAndSkipsWrite() {
        ProvisioningProfileService ps = mock(ProvisioningProfileService.class);
        UUID profileId = UUID.randomUUID();
        String dn = "uid=jsmith,ou=people,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setId(profileId);
        when(ps.resolveProfileForDn(dirId, dn)).thenReturn(Optional.of(profile));
        doThrow(new IllegalArgumentException(
                "Attribute [employeeNumber] is not editable on update"))
                .when(ps).validateModification(eq(profileId), any(), any());

        LdapOperationService svc = serviceWithProfile(ps);
        UpdateEntryRequest req = new UpdateEntryRequest(List.of(
                new AttributeModification(AttributeModification.Operation.REPLACE,
                        "employeeNumber", List.of("999"))));

        assertThatThrownBy(() -> svc.updateUser(dirId, adminPrincipal(), dn, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not editable");

        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    // ── Group operations ──────────────────────────────────────────────────────

    @Test
    void addGroupMember_callsGroupService() {
        String groupDn = "cn=Staff,ou=Groups,dc=example,dc=com";
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        service.addGroupMember(dirId, adminPrincipal(), groupDn, "member", "cn=Alice,ou=Users");

        verify(groupService).addMember(dc, groupDn, "member", "cn=Alice,ou=Users", null);
    }

    @Test
    void createGroup_malformedDn_throwsAndSkipsWrite() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        CreateEntryRequest req = new CreateEntryRequest(
                "cn=Staff,ou", Map.of("cn", List.of("Staff")));

        assertThatThrownBy(() -> service.createGroup(dirId, adminPrincipal(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid DN");

        verify(groupService, never()).createGroup(any(), anyString(), any());
    }

    @Test
    void searchUsers_limitIsPassedToUserService() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        // Admin path: simulate a single authorized OU so the service
        // uses the single-base branch.
        when(permissionService.resolveSearchBaseDns(any(), eq(dirId), any()))
                .thenReturn(java.util.Collections.singletonList("ou=Users,dc=example,dc=com"));

        when(userService.searchUsers(eq(dc), anyString(), any(), eq(2), any(String[].class)))
                .thenReturn(List.of(
                        new LdapUser("cn=A,dc=example,dc=com", Map.of()),
                        new LdapUser("cn=B,dc=example,dc=com", Map.of())));

        List<LdapEntryResponse> result = service.searchUsers(
                dirId, adminPrincipal(), null, null, 2, new String[0]);

        assertThat(result).hasSize(2);
        verify(userService).searchUsers(eq(dc), anyString(), any(), eq(2), any(String[].class));
    }

    // ── Batch membership changes ──────────────────────────────────────────────

    private static final String MEMBER_DN = "uid=alice,ou=people,dc=example,dc=com";
    private static final String GROUP_A   = "cn=devs,ou=groups,dc=example,dc=com";
    private static final String GROUP_B   = "cn=ops,ou=groups,dc=example,dc=com";

    private MembershipChangeRequest.Change add(String groupDn) {
        return new MembershipChangeRequest.Change(groupDn, "member", MembershipChangeRequest.Op.ADD);
    }

    private MembershipChangeRequest.Change remove(String groupDn) {
        return new MembershipChangeRequest.Change(groupDn, "member", MembershipChangeRequest.Op.REMOVE);
    }

    @Test
    void applyMembershipChanges_appliesRemovesBeforeAdds() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        // Request order is add-then-remove; the service must reorder so the
        // remove runs first.
        MembershipChangeResult result = service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A), remove(GROUP_B)));

        assertThat(result.applied()).isEqualTo(2);
        assertThat(result.queued()).isZero();

        var ord = inOrder(groupService);
        ord.verify(groupService).removeMember(eq(dc), eq(GROUP_B), eq("member"), eq(MEMBER_DN));
        ord.verify(groupService).addMember(eq(dc), eq(GROUP_A), eq("member"), eq(MEMBER_DN), isNull());
    }

    @Test
    void applyMembershipChanges_queuesAddWhenApprovalRequired() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));

        ApprovalWorkflowService approval = mock(ApprovalWorkflowService.class);
        PendingApproval pa = mock(PendingApproval.class);
        UUID approvalId = UUID.randomUUID();
        when(pa.getId()).thenReturn(approvalId);
        when(approval.checkAndSubmitForApproval(eq(dirId), eq(GROUP_A), any(),
                eq(ApprovalRequestType.GROUP_MEMBER_ADD), any())).thenReturn(Optional.of(pa));

        MembershipChangeResult result = serviceWithApproval(approval)
                .applyMembershipChanges(dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A)));

        assertThat(result.queued()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        assertThat(result.items().get(0).status())
                .isEqualTo(MembershipChangeResult.Status.QUEUED_FOR_APPROVAL);
        assertThat(result.items().get(0).approvalId()).isEqualTo(approvalId);
        // Queued — never written to the directory.
        verify(groupService, never()).addMember(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void applyMembershipChanges_classifiesRefusalPerItem() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        doThrow(new ProvisioningRefusedException("target lacks secGroup"))
                .when(groupService).addMember(eq(dc), eq(GROUP_A), eq("member"), eq(MEMBER_DN), isNull());

        MembershipChangeResult result = service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A)));

        assertThat(result.refused()).isEqualTo(1);
        assertThat(result.items().get(0).status())
                .isEqualTo(MembershipChangeResult.Status.REFUSED);
        assertThat(result.items().get(0).message()).contains("secGroup");
    }

    @Test
    void applyMembershipChanges_outOfScopeMember_aborts() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        doThrow(new AccessDeniedException("nope"))
                .when(permissionService).requireDnWithinScope(any(), eq(dirId), eq(MEMBER_DN));

        assertThatThrownBy(() -> service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A))))
                .isInstanceOf(AccessDeniedException.class);

        verify(groupService, never()).addMember(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void applyMembershipChanges_outOfScopeGroup_abortsBeforeAnyWrite() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        // The member is in scope but one target group is not: the pre-flight
        // check must 403 before any write, never half-applying the batch.
        // Lenient: the member + GROUP_A scope checks run first and don't match
        // this stub, which would otherwise trip strict-stubbing.
        org.mockito.Mockito.lenient().doThrow(new AccessDeniedException("nope"))
                .when(permissionService).requireDnWithinScope(any(), eq(dirId), eq(GROUP_B));

        assertThatThrownBy(() -> service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(remove(GROUP_A), add(GROUP_B))))
                .isInstanceOf(AccessDeniedException.class);

        verify(groupService, never()).addMember(any(), anyString(), anyString(), anyString(), any());
        verify(groupService, never()).removeMember(any(), anyString(), anyString(), anyString());
    }

    @Test
    void applyMembershipChanges_addAlreadyMember_reportedApplied() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        // groupService surfaces the LDAP "value already exists" code wrapped the
        // same way the real write path does (LDAPException as the cause).
        doThrow(new LdapOperationException("dup",
                new LDAPException(ResultCode.ATTRIBUTE_OR_VALUE_EXISTS, "exists")))
                .when(groupService).addMember(eq(dc), eq(GROUP_A), eq("member"), eq(MEMBER_DN), isNull());

        MembershipChangeResult result = service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A)));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.errored()).isZero();
        assertThat(result.items().get(0).status())
                .isEqualTo(MembershipChangeResult.Status.APPLIED);
    }

    @Test
    void applyMembershipChanges_removeNonMember_reportedApplied() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        doThrow(new LdapOperationException("no value",
                new LDAPException(ResultCode.NO_SUCH_ATTRIBUTE, "absent")))
                .when(groupService).removeMember(eq(dc), eq(GROUP_A), eq("member"), eq(MEMBER_DN));

        MembershipChangeResult result = service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(remove(GROUP_A)));

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.errored()).isZero();
    }

    @Test
    void applyMembershipChanges_realLdapError_stillReportedAsError() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        // A genuine failure (not the idempotent no-op code) stays an error.
        doThrow(new LdapOperationException("boom",
                new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "nope")))
                .when(groupService).addMember(eq(dc), eq(GROUP_A), eq("member"), eq(MEMBER_DN), isNull());

        MembershipChangeResult result = service.applyMembershipChanges(
                dirId, adminPrincipal(), MEMBER_DN, List.of(add(GROUP_A)));

        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        assertThat(result.items().get(0).status())
                .isEqualTo(MembershipChangeResult.Status.ERROR);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a service whose approval provider yields the given workflow service. */
    private LdapOperationService serviceWithApproval(ApprovalWorkflowService approval) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> profileProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(profileProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ApprovalWorkflowService> approvalProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(approvalProvider.getIfAvailable()).thenReturn(approval);
        return new LdapOperationService(
                dirRepo, permissionService, browseService, userService, groupService,
                schemaService, auditService, bulkUserService, bulkGroupService, csvTemplateService,
                membershipGate, profileProvider, approvalProvider);
    }

    /** Builds a service whose ObjectProvider yields the given profile service. */
    private LdapOperationService serviceWithProfile(ProvisioningProfileService ps) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(ps);
        return new LdapOperationService(
                dirRepo, permissionService, browseService, userService, groupService,
                schemaService, auditService, bulkUserService, bulkGroupService, csvTemplateService,
                membershipGate, provider, nullApprovalProvider());
    }

    /** An ApprovalWorkflowService provider that yields null (no approval gating). */
    private org.springframework.beans.factory.ObjectProvider<ApprovalWorkflowService> nullApprovalProvider() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ApprovalWorkflowService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    // ── Bulk delete ─────────────────────────────────────────────────────────────

    private com.ldapportal.service.BulkUserService.RawDeleteRow rawRow(int n, String v) {
        return new com.ldapportal.service.BulkUserService.RawDeleteRow(n, v);
    }

    private com.ldapportal.dto.csv.BulkDeleteRequest dnDeleteReq() {
        return new com.ldapportal.dto.csv.BulkDeleteRequest(null, null, null, true);
    }

    @Test
    void bulkDelete_dnMode_deletesResolvedRowsAndRecordsSummaryWithDeletedDns() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String dn = "uid=a,ou=people,dc=example,dc=com";
        when(bulkUserService.parseDeleteRows(any(), eq("dn"), eq(true)))
                .thenReturn(List.of(rawRow(1, dn)));
        when(userService.entryExists(dc, dn)).thenReturn(true);

        var result = service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), dnDeleteReq());

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r.status())
                        .isEqualTo(com.ldapportal.dto.csv.BulkDeleteRowResult.Status.DELETED));
        verify(userService).deleteUser(dc, dn, null);

        // One summary audit record naming the deleted DN.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(any(), eq(dirId),
                eq(com.ldapportal.entity.enums.AuditAction.USER_DELETE), isNull(), detail.capture());
        assertThat(detail.getValue()).containsEntry("operation", "bulkDelete");
        assertThat(detail.getValue().get("deletedDns")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).containsExactly(dn);
    }

    @Test
    void bulkImport_recordsSummaryWithCreatedDns() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String createdDn = "uid=jdoe,ou=people,dc=example,dc=com";
        // One created row + one errored row: only the created DN should be listed.
        BulkImportResult importResult = new BulkImportResult(2, 1, 0, 0, 1,
                List.of(BulkImportRowResult.created(1, createdDn),
                        BulkImportRowResult.error(2, null, "Missing value for key attribute 'uid'")));
        when(bulkUserService.importCsv(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), any())).thenReturn(importResult);

        BulkImportRequest req = new BulkImportRequest(
                null, "ou=people,dc=example,dc=com", null, null, true, null, List.of());
        var result = service.bulkImportUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), req);

        assertThat(result.created()).isEqualTo(1);

        // One summary audit record naming exactly the created DN — symmetric
        // with bulkDelete's deletedDns; the errored row is not listed.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(any(), eq(dirId),
                eq(com.ldapportal.entity.enums.AuditAction.USER_CREATE),
                eq("ou=people,dc=example,dc=com"), detail.capture());
        assertThat(detail.getValue()).containsEntry("operation", "bulkImport");
        assertThat(detail.getValue().get("createdDns")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).containsExactly(createdDn);
    }

    @Test
    void bulkDelete_dnMode_notFoundIsSkippedNotDeleted() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String dn = "uid=ghost,ou=people,dc=example,dc=com";
        when(bulkUserService.parseDeleteRows(any(), eq("dn"), eq(true)))
                .thenReturn(List.of(rawRow(1, dn)));
        when(userService.entryExists(dc, dn)).thenReturn(false);

        var result = service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), dnDeleteReq());

        assertThat(result.deleted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(userService, never()).deleteUser(any(), anyString(), any());
    }

    @Test
    void bulkDelete_outOfScopeRow_isErrorAndNeverDeleted() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String dn = "uid=x,ou=forbidden,dc=example,dc=com";
        when(bulkUserService.parseDeleteRows(any(), eq("dn"), eq(true)))
                .thenReturn(List.of(rawRow(1, dn)));
        doThrow(new AccessDeniedException("nope"))
                .when(permissionService).requireDnWithinScope(any(), eq(dirId), eq(dn));

        var result = service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), dnDeleteReq());

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        // Out-of-scope DNs are never even probed for existence.
        verify(userService, never()).entryExists(any(), anyString());
        verify(userService, never()).deleteUser(any(), anyString(), any());
    }

    @Test
    void bulkDelete_keyMode_ambiguousMatchIsErrorNotDeleted() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String base = "ou=people,dc=example,dc=com";
        var req = new com.ldapportal.dto.csv.BulkDeleteRequest("uid", null, base, true);
        when(bulkUserService.parseDeleteRows(any(), eq("uid"), eq(true)))
                .thenReturn(List.of(rawRow(1, "dup")));
        when(bulkUserService.resolveDnsByKey(dc, "uid", "dup", base))
                .thenReturn(List.of("uid=dup,ou=a," + base, "uid=dup,ou=b," + base));

        var result = service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), req);

        assertThat(result.errors()).isEqualTo(1);
        verify(userService, never()).deleteUser(any(), anyString(), any());
    }

    @Test
    void bulkDelete_keyMode_requiresBaseDn() {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        var req = new com.ldapportal.dto.csv.BulkDeleteRequest("uid", null, null, true);

        assertThatThrownBy(() -> service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDn");
    }

    @Test
    void bulkDelete_overRowCap_rejected() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        List<com.ldapportal.service.BulkUserService.RawDeleteRow> tooMany =
                new java.util.ArrayList<>();
        for (int i = 1; i <= LdapOperationService.MAX_BULK_DELETE_ROWS + 1; i++) {
            tooMany.add(rawRow(i, "uid=" + i + ",ou=people,dc=example,dc=com"));
        }
        when(bulkUserService.parseDeleteRows(any(), eq("dn"), eq(true))).thenReturn(tooMany);

        assertThatThrownBy(() -> service.bulkDeleteUsers(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), dnDeleteReq()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to");
        verify(userService, never()).deleteUser(any(), anyString(), any());
    }

    @Test
    void previewBulkDelete_classifiesWithoutDeleting() throws Exception {
        DirectoryConnection dc = enabledDir(true);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        String present = "uid=here,ou=people,dc=example,dc=com";
        String absent  = "uid=gone,ou=people,dc=example,dc=com";
        when(bulkUserService.parseDeleteRows(any(), eq("dn"), eq(true)))
                .thenReturn(List.of(rawRow(1, present), rawRow(2, absent)));
        when(userService.entryExists(dc, present)).thenReturn(true);
        when(userService.entryExists(dc, absent)).thenReturn(false);

        var preview = service.previewBulkDelete(dirId, adminPrincipal(),
                new java.io.ByteArrayInputStream(new byte[0]), dnDeleteReq());

        assertThat(preview.rows()).extracting(r -> r.disposition()).containsExactly(
                com.ldapportal.dto.csv.BulkDeletePreviewRow.Disposition.WILL_DELETE,
                com.ldapportal.dto.csv.BulkDeletePreviewRow.Disposition.NOT_FOUND);
        verify(userService, never()).deleteUser(any(), anyString(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    private AuthPrincipal adminPrincipal() {
        return new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");
    }

    private AuthPrincipal superadminPrincipal() {
        return new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "superadmin");
    }

    private DirectoryConnection enabledDir(boolean enabled) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setEnabled(enabled);
        dc.setDisplayName("test-dir");
        dc.setBaseDn("dc=example,dc=com");
        dc.setPagingSize(500);
        return dc;
    }
}
