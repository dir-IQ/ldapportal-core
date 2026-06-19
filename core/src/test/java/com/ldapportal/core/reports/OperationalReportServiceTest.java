// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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

    private OperationalReportService service;

    private final UUID dirId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OperationalReportService(
                userService, groupService, auditEventRepo, profileRepo, profileService, List.of());
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
}
