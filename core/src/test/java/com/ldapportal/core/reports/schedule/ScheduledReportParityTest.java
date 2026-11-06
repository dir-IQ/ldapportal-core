// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.reports.OperationalReportService;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.service.ProvisioningProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A scheduled run of an admin's job must produce what that admin gets running
 * the same report on demand. Wires the real {@link OperationalReportService}
 * behind the real scheduled content provider (only the directory, audit store
 * and permission lookups are mocked) and compares the two paths' output.
 *
 * <p>The admin holds a user OU and a separate group OU; the job is pinned to
 * the user OU, as {@code withCreatorScope} pins it at save time.</p>
 */
class ScheduledReportParityTest {

    private static final String PEOPLE = "ou=people,dc=example,dc=com";
    private static final String GROUPS = "ou=groups,dc=example,dc=com";
    private static final String OTHER  = "ou=other,dc=example,dc=com";

    private final LdapUserService userService = mock(LdapUserService.class);
    private final LdapGroupService groupService = mock(LdapGroupService.class);
    private final AuditEventRepository auditEventRepo = mock(AuditEventRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);

    private final UUID dirId = UUID.randomUUID();
    private final AuthPrincipal alice = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "alice");

    private OperationalReportService reports;
    private OperationalScheduledReportContentProvider scheduled;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() {
        reports = new OperationalReportService(userService, groupService, auditEventRepo,
                mock(ProvisioningProfileRepository.class), mock(ProvisioningProfileService.class),
                permissionService, List.of());
        scheduled = new OperationalScheduledReportContentProvider(reports, List.of());
        dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setBaseDn("dc=example,dc=com");

        when(permissionService.resolveSearchBaseDns(alice, dirId, PEOPLE)).thenReturn(List.of(PEOPLE));
        when(permissionService.getAuthorizedOuDns(alice, dirId)).thenReturn(Set.of(PEOPLE, GROUPS));
        when(permissionService.isDnWithinScope(eq(alice), eq(dirId), anyString())).thenAnswer(i -> {
            String dn = i.getArgument(2);
            return dn.endsWith(PEOPLE) || dn.endsWith(GROUPS);
        });
    }

    /** The job's stored params: pinned to the admin's user OU. */
    private Map<String, Object> jobParams(Map<String, Object> extra) {
        Map<String, Object> p = new java.util.HashMap<>(extra);
        p.put("scopeBaseDn", PEOPLE);
        return p;
    }

    private ReportData onDemand(String type, Map<String, Object> params) {
        return reports.run(dc, type, params, dirId, alice);
    }

    private ReportData scheduledRun(String type, Map<String, Object> params) {
        return scheduled.run(dc, type, params, PEOPLE, alice);
    }

    private static AuditEvent event(AuditAction action, String targetDn) {
        return AuditEvent.builder().id(UUID.randomUUID()).occurredAt(OffsetDateTime.now())
                .actorUsername("x").action(action).targetDn(targetDn).build();
    }

    @Test
    void auditEntries_dropRowsOutsideTheAdminsOus_inBothPaths() {
        when(auditEventRepo.findAll(eq(dirId), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        event(AuditAction.USER_UPDATE, "uid=in," + PEOPLE),
                        event(AuditAction.USER_UPDATE, "uid=out," + OTHER))));
        Map<String, Object> params = jobParams(Map.of());

        ReportData live = onDemand("AUDIT_ENTRIES", params);
        ReportData job = scheduledRun("AUDIT_ENTRIES", params);

        assertThat(job).isEqualTo(live);
        assertThat(job.rows()).extracting(r -> r.get("Target")).containsExactly("uid=in," + PEOPLE);
    }

    @Test
    void recentlyDeleted_dropRowsOutsideTheAdminsOus_inBothPaths() {
        when(auditEventRepo.findAll(eq(dirId), isNull(), eq(AuditAction.USER_DELETE.name()), isNull(), isNull(),
                isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        event(AuditAction.USER_DELETE, "uid=in," + PEOPLE),
                        event(AuditAction.USER_DELETE, "uid=out," + OTHER))));
        when(auditEventRepo.findAll(eq(dirId), isNull(), eq(AuditAction.GROUP_DELETE.name()), isNull(), isNull(),
                isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(auditEventRepo.findAll(eq(dirId), isNull(), eq(AuditAction.LDAP_CHANGE.name()), isNull(), isNull(),
                isNull(), any(OffsetDateTime.class), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        Map<String, Object> params = jobParams(Map.of("lookbackDays", 7));

        ReportData live = onDemand("RECENTLY_DELETED", params);
        ReportData job = scheduledRun("RECENTLY_DELETED", params);

        assertThat(job).isEqualTo(live);
        assertThat(job.rows()).extracting(r -> r.get("Entry")).containsExactly("uid=in," + PEOPLE);
    }

    @Test
    void groupsColumn_namesGroupsInTheAdminsGroupOu_inBothPaths() {
        when(userService.searchUsersRaw(eq(dc), anyString(), eq(PEOPLE), anyInt(), any(String[].class)))
                .thenReturn(List.of(new LdapUser("uid=bob," + PEOPLE, Map.of("uid", List.of("bob")))));
        LdapGroup devs = new LdapGroup("cn=devs," + GROUPS,
                Map.of("cn", List.of("devs"), "member", List.of("uid=bob," + PEOPLE)));
        when(groupService.searchGroups(eq(dc), anyString(), eq(GROUPS), any(String[].class)))
                .thenReturn(List.of(devs));
        when(groupService.searchGroups(eq(dc), anyString(), eq(PEOPLE), any(String[].class)))
                .thenReturn(List.of());
        Map<String, Object> params = jobParams(Map.of());

        ReportData live = onDemand("DISABLED_ACCOUNTS", params);
        ReportData job = scheduledRun("DISABLED_ACCOUNTS", params);

        assertThat(job).isEqualTo(live);
        assertThat(job.rows()).singleElement()
                .satisfies(row -> assertThat(row.get(job.columns().get(job.columns().size() - 1)))
                        .isEqualTo("devs"));
    }

    @Test
    void usersInGroup_dropMembersOutsideTheAdminsOus_inBothPaths() {
        String groupDn = "cn=mixed," + GROUPS;
        LdapGroup mixed = new LdapGroup(groupDn,
                Map.of("cn", List.of("mixed"), "member", List.of("uid=in," + PEOPLE, "uid=out," + OTHER)));
        when(groupService.getGroup(eq(dc), eq(groupDn), any(String[].class))).thenReturn(mixed);
        when(userService.searchUsersRaw(eq(dc), anyString(), eq("uid=in," + PEOPLE), anyInt(), any(String[].class)))
                .thenReturn(List.of(new LdapUser("uid=in," + PEOPLE, Map.of("cn", List.of("In")))));
        when(userService.searchUsersRaw(eq(dc), anyString(), eq("uid=out," + OTHER), anyInt(), any(String[].class)))
                .thenReturn(List.of(new LdapUser("uid=out," + OTHER, Map.of("cn", List.of("Out")))));
        when(groupService.searchGroups(eq(dc), anyString(), anyString(), any(String[].class)))
                .thenReturn(List.of());
        Map<String, Object> params = jobParams(Map.of("groupDn", groupDn));

        ReportData live = onDemand("USERS_IN_GROUP", params);
        ReportData job = scheduledRun("USERS_IN_GROUP", params);

        assertThat(job).isEqualTo(live);
        assertThat(job.rows()).extracting(r -> r.get("dn")).containsExactly("uid=in," + PEOPLE);
    }

    @Test
    void scheduledRun_isRefused_onceTheAdminNoLongerHoldsTheJobsOu() {
        when(permissionService.resolveSearchBaseDns(alice, dirId, PEOPLE))
                .thenThrow(new AccessDeniedException("Search baseDn is outside authorized OUs for this admin"));

        assertThatThrownBy(() -> scheduledRun("DISABLED_ACCOUNTS", jobParams(Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
