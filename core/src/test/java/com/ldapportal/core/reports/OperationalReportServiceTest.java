// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.service.ProvisioningProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Focused tests for the {@code AUDIT_ENTRIES} operational report — lookback
 * window (hours) + optional action filter, mapped from audit events to rows.
 */
@ExtendWith(MockitoExtension.class)
class OperationalReportServiceTest {

    @Mock private LdapUserService               userService;
    @Mock private LdapGroupService              groupService;
    @Mock private AuditEventRepository          auditEventRepo;
    @Mock private ProvisioningProfileRepository profileRepo;
    @Mock private ProvisioningProfileService    profileService;
    @Mock private PermissionService             permissionService;

    private OperationalReportService service;

    private final UUID dirId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OperationalReportService(
                userService, groupService, auditEventRepo, profileRepo, profileService,
                permissionService, List.of());
    }

    private DirectoryConnection ldapDir() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setBaseDn("dc=example,dc=com");
        return dc;
    }

    @Test
    void auditEntries_filtersByLookbackAndActions_andMapsRows() {
        AuditEvent e = AuditEvent.builder()
                .id(UUID.randomUUID())
                .occurredAt(OffsetDateTime.parse("2026-06-19T10:00:00Z"))
                .actorUsername("alice")
                .action(AuditAction.USER_CREATE)
                .targetDn("uid=bob,ou=people,dc=example,dc=com")
                .detail(Map.of("operation", "create"))
                .build();
        when(auditEventRepo.findAll(eq(dirId), isNull(), any(String.class),
                isNull(), isNull(), isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e)));

        Map<String, Object> params = Map.of(
                "lookbackHours", 12,
                "actions", List.of("USER_CREATE", "USER_UPDATE"));
        ReportData data = service.run(ldapDir(), "AUDIT_ENTRIES", params, dirId);

        assertThat(data.columns()).containsExactly("When", "Actor", "Action", "Target", "Detail");
        assertThat(data.rows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("Actor", "alice");
            assertThat(row).containsEntry("Action", "USER_CREATE");
            assertThat(row).containsEntry("Target", "uid=bob,ou=people,dc=example,dc=com");
            assertThat(row.get("Detail")).contains("operation: create");
        });

        // Action filter is the comma-joined enum NAMES (the audit_events.action
        // column stores names, not dbValues).
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditEventRepo).findAll(eq(dirId), isNull(), action.capture(),
                isNull(), isNull(), isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class));
        assertThat(action.getValue()).isEqualTo("USER_CREATE,USER_UPDATE");
    }

    @Test
    void auditEntries_noActions_passesNullFilter() {
        when(auditEventRepo.findAll(eq(dirId), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ReportData data = service.run(ldapDir(), "AUDIT_ENTRIES", Map.of(), dirId);

        assertThat(data.rows()).isEmpty();
        verify(auditEventRepo).findAll(eq(dirId), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class));
    }

    @Test
    void auditEntries_invalidActionName_throws400() {
        assertThatThrownBy(() -> service.run(ldapDir(), "AUDIT_ENTRIES",
                Map.of("actions", List.of("NOT_A_REAL_ACTION")), dirId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Scope enforcement: reports read no further than the caller can browse ──

    private static final String PEOPLE = "ou=people,dc=example,dc=com";
    private static final String OTHER  = "ou=other,dc=example,dc=com";

    private AuthPrincipal admin() {
        return new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "alice");
    }

    private AuthPrincipal superadmin() {
        return new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");
    }

    @Test
    void scope_admin_withoutRequestedScope_runsOverAuthorizedOu() {
        AuthPrincipal alice = admin();
        DirectoryConnection dc = ldapDir();
        when(permissionService.resolveSearchBaseDns(alice, dirId, null)).thenReturn(List.of(PEOPLE));

        service.run(dc, "DISABLED_ACCOUNTS", Map.of(), dirId, alice);

        // Not the directory base the old code fell back to.
        verify(userService).searchUsers(eq(dc), anyString(), eq(PEOPLE), anyInt(), eq("*"));
    }

    @Test
    void scope_admin_requestedScopeIsClampedByThePermissionService() {
        AuthPrincipal alice = admin();
        DirectoryConnection dc = ldapDir();
        // The client asked for the directory root; the permission service
        // clamps that to the OU the admin actually holds.
        when(permissionService.resolveSearchBaseDns(alice, dirId, "dc=example,dc=com"))
                .thenReturn(List.of(PEOPLE));

        service.run(dc, "RECENTLY_ADDED",
                Map.of("scopeBaseDn", "dc=example,dc=com", "lookbackDays", 7), dirId, alice);

        verify(userService).searchUsers(eq(dc), anyString(), eq(PEOPLE), anyInt(), eq("*"));
    }

    @Test
    void scope_admin_scopeOutsideTheirOus_isRefused() {
        AuthPrincipal alice = admin();
        when(permissionService.resolveSearchBaseDns(alice, dirId, OTHER))
                .thenThrow(new AccessDeniedException("outside"));

        assertThatThrownBy(() -> service.run(ldapDir(), "DISABLED_ACCOUNTS",
                Map.of("scopeBaseDn", OTHER), dirId, alice))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(userService);
    }

    // ── MISSING_DATA ────────────────────────────────────────────────────────

    private static final String BRANCH = "ou=people,dc=example,dc=com";

    private static LdapUser entry(String rdn, Map<String, List<String>> attrs) {
        return new LdapUser(rdn + "," + BRANCH, attrs);
    }

    @Test
    void missingData_listsEntriesWithAbsentOrBlankAttributes_andSkipsPopulatedOnes() {
        DirectoryConnection dc = ldapDir();
        when(userService.searchUsers(eq(dc), eq("(objectClass=*)"), eq(BRANCH), anyInt(), any(String[].class)))
                .thenReturn(List.of(
                        // fully populated → not reported
                        entry("uid=ok", Map.of("mail", List.of("ok@example.com"), "sn", List.of("Ok"),
                                "objectclass", List.of("inetOrgPerson"))),
                        // mail absent
                        entry("uid=nomail", Map.of("sn", List.of("Nomail"),
                                "objectclass", List.of("inetOrgPerson"))),
                        // sn present but blank, mail multi-valued with one real value → only sn
                        entry("uid=blanksn", Map.of("mail", List.of("", "x@example.com"), "sn", List.of("  "),
                                "objectclass", List.of("inetOrgPerson"))),
                        // both missing
                        entry("uid=none", Map.of("objectclass", List.of("inetOrgPerson"))),
                        // the branch entry itself is never reported
                        new LdapUser(BRANCH, Map.of("objectclass", List.of("organizationalUnit")))));

        ReportData data = service.run(dc, "MISSING_DATA",
                Map.of("branchDn", BRANCH, "attributes", List.of("mail", "sn")), dirId, superadmin());

        assertThat(data.columns()).containsExactly("DN", "Missing Attributes", "Object Class", "Email", "Last Name");
        assertThat(data.rows()).extracting(r -> r.get("DN"))
                .containsExactly("uid=nomail," + BRANCH, "uid=blanksn," + BRANCH, "uid=none," + BRANCH);
        assertThat(data.rows().get(0).get("Missing Attributes")).isEqualTo("mail");
        assertThat(data.rows().get(1).get("Missing Attributes")).isEqualTo("sn");
        assertThat(data.rows().get(1).get("Email")).isEqualTo("|x@example.com");
        assertThat(data.rows().get(2).get("Missing Attributes")).isEqualTo("mail, sn");
        assertThat(data.rows().get(2).get("Object Class")).isEqualTo("inetOrgPerson");
    }

    @Test
    void missingData_acceptsCommaSeparatedAttributes_andFetchesOnlyThosePlusObjectClass() {
        DirectoryConnection dc = ldapDir();
        when(userService.searchUsers(eq(dc), anyString(), eq(BRANCH), anyInt(), any(String[].class)))
                .thenReturn(List.of());

        service.run(dc, "MISSING_DATA",
                Map.of("branchDn", BRANCH, "attributes", " mail, telephoneNumber ,,MAIL "), dirId, superadmin());

        ArgumentCaptor<String[]> attrs = ArgumentCaptor.forClass(String[].class);
        verify(userService).searchUsers(eq(dc), eq("(objectClass=*)"), eq(BRANCH), anyInt(), attrs.capture());
        assertThat(attrs.getValue()).containsExactly("mail", "telephoneNumber", "objectClass");
    }

    @Test
    void missingData_objectTypeUser_narrowsTheSearchFilter() {
        DirectoryConnection dc = ldapDir();
        when(userService.searchUsers(eq(dc), anyString(), eq(BRANCH), anyInt(), any(String[].class)))
                .thenReturn(List.of());

        service.run(dc, "MISSING_DATA",
                Map.of("branchDn", BRANCH, "attributes", List.of("mail"), "objectType", "USER"),
                dirId, superadmin());

        verify(userService).searchUsers(eq(dc),
                eq("(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))"),
                eq(BRANCH), anyInt(), any(String[].class));
    }

    @Test
    void missingData_requiresBranchAndAtLeastOneAttribute() {
        assertThatThrownBy(() -> service.run(ldapDir(), "MISSING_DATA",
                Map.of("attributes", List.of("mail")), dirId, superadmin()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branchDn");
        assertThatThrownBy(() -> service.run(ldapDir(), "MISSING_DATA",
                Map.of("branchDn", BRANCH, "attributes", " , "), dirId, superadmin()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributes");
        assertThatThrownBy(() -> service.run(ldapDir(), "MISSING_DATA",
                Map.of("branchDn", BRANCH), dirId, superadmin()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributes");
        verifyNoInteractions(userService);
    }

    @Test
    void missingData_rejectsMalformedAttributeNames() {
        assertThatThrownBy(() -> service.run(ldapDir(), "MISSING_DATA",
                Map.of("branchDn", BRANCH, "attributes", List.of("mail", "sn)(cn=*")), dirId, superadmin()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sn)(cn=*");
        verifyNoInteractions(userService);
    }

    @Test
    void missingData_admin_branchOutsideTheirOus_isRefused() {
        AuthPrincipal alice = admin();
        when(permissionService.resolveSearchBaseDns(alice, dirId, null)).thenReturn(List.of(PEOPLE));
        when(permissionService.isDnWithinScope(alice, dirId, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.run(ldapDir(), "MISSING_DATA",
                Map.of("branchDn", OTHER, "attributes", List.of("mail")), dirId, alice))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(userService);
    }

    @Test
    void scope_admin_branchOutsideTheirOus_isRefused() {
        AuthPrincipal alice = admin();
        when(permissionService.resolveSearchBaseDns(alice, dirId, null)).thenReturn(List.of(PEOPLE));
        when(permissionService.isDnWithinScope(alice, dirId, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.run(ldapDir(), "USERS_IN_BRANCH",
                Map.of("branchDn", OTHER), dirId, alice))
                .isInstanceOf(AccessDeniedException.class);
        verify(userService, never()).searchUsers(any(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void scope_admin_severalOus_mergesRowsAcrossThem() {
        AuthPrincipal alice = admin();
        DirectoryConnection dc = ldapDir();
        when(permissionService.resolveSearchBaseDns(alice, dirId, null)).thenReturn(List.of(PEOPLE, OTHER));
        when(userService.searchUsers(eq(dc), anyString(), eq(PEOPLE), anyInt(), eq("*")))
                .thenReturn(List.of(new LdapUser("uid=a," + PEOPLE, Map.of("cn", List.of("A")))));
        when(userService.searchUsers(eq(dc), anyString(), eq(OTHER), anyInt(), eq("*")))
                .thenReturn(List.of(new LdapUser("uid=b," + OTHER, Map.of("cn", List.of("B")))));

        ReportData data = service.run(dc, "DISABLED_ACCOUNTS", Map.of(), dirId, alice);

        assertThat(data.rows()).hasSize(2);
    }

    @Test
    void scope_admin_auditEntriesOutsideTheirOus_areDropped() {
        AuthPrincipal alice = admin();
        when(permissionService.resolveSearchBaseDns(alice, dirId, null)).thenReturn(List.of(PEOPLE));
        when(permissionService.isDnWithinScope(alice, dirId, "uid=in," + PEOPLE)).thenReturn(true);
        when(permissionService.isDnWithinScope(alice, dirId, "uid=out," + OTHER)).thenReturn(false);
        AuditEvent in = AuditEvent.builder().id(UUID.randomUUID())
                .occurredAt(OffsetDateTime.parse("2026-06-19T10:00:00Z")).actorUsername("x")
                .action(AuditAction.USER_UPDATE).targetDn("uid=in," + PEOPLE).build();
        AuditEvent out = AuditEvent.builder().id(UUID.randomUUID())
                .occurredAt(OffsetDateTime.parse("2026-06-19T10:00:00Z")).actorUsername("x")
                .action(AuditAction.USER_UPDATE).targetDn("uid=out," + OTHER).build();
        when(auditEventRepo.findAll(eq(dirId), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(in, out)));

        ReportData data = service.run(ldapDir(), "AUDIT_ENTRIES", Map.of(), dirId, alice);

        assertThat(data.rows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("Target", "uid=in," + PEOPLE));
    }

    @Test
    void scope_superadmin_isUnbounded_andNeverConsultsThePermissionService() {
        DirectoryConnection dc = ldapDir();

        service.run(dc, "DISABLED_ACCOUNTS", Map.of(), dirId, superadmin());

        verify(userService).searchUsers(eq(dc), anyString(), isNull(), anyInt(), eq("*"));
        verifyNoInteractions(permissionService);
    }

    @Test
    void scope_systemRun_usesTheStoredScopeAsIs() {
        DirectoryConnection dc = ldapDir();

        service.run(dc, "DISABLED_ACCOUNTS", Map.of("scopeBaseDn", PEOPLE), dirId);

        verify(userService).searchUsers(eq(dc), anyString(), eq(PEOPLE), anyInt(), eq("*"));
        verifyNoInteractions(permissionService);
    }
}

