// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.PendingApprovalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the inventory gauges snapshot the right repository counts (including
 * the per-role admin breakdown) and that a repository failure during refresh is
 * swallowed rather than propagated. Repositories are mocked; meters ride a real
 * {@link SimpleMeterRegistry}.
 */
class InventoryMetricsTest {

    private DirectoryConnectionRepository directoryRepo;
    private AccountRepository accountRepo;
    private EventSubscriptionRepository subscriptionRepo;
    private PendingApprovalRepository approvalRepo;
    private SimpleMeterRegistry registry;
    private InventoryMetrics metrics;

    @BeforeEach
    void setUp() {
        directoryRepo = mock(DirectoryConnectionRepository.class);
        accountRepo = mock(AccountRepository.class);
        subscriptionRepo = mock(EventSubscriptionRepository.class);
        approvalRepo = mock(PendingApprovalRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new InventoryMetrics(directoryRepo, accountRepo, subscriptionRepo, approvalRepo, registry);
    }

    @Test
    void snapshots_each_inventory_count() {
        when(directoryRepo.count()).thenReturn(3L);
        when(accountRepo.countByRoleAndActiveTrue(AccountRole.ADMIN)).thenReturn(5L);
        when(accountRepo.countByRoleAndActiveTrue(AccountRole.SUPERADMIN)).thenReturn(2L);
        when(subscriptionRepo.countByEnabledTrue()).thenReturn(4L);
        when(approvalRepo.countByStatus(ApprovalStatus.PENDING)).thenReturn(7L);

        metrics.refresh();

        assertThat(gauge("ldapportal.inventory.directories")).isEqualTo(3.0);
        assertThat(role("admin")).isEqualTo(5.0);
        assertThat(role("superadmin")).isEqualTo(2.0);
        assertThat(gauge("ldapportal.inventory.event.subscribers")).isEqualTo(4.0);
        assertThat(gauge("ldapportal.inventory.pending.approvals")).isEqualTo(7.0);
    }

    @Test
    void pending_approval_backlog_age_is_computed_from_the_oldest() {
        when(approvalRepo.findOldestCreatedAtByStatus(ApprovalStatus.PENDING))
                .thenReturn(OffsetDateTime.now().minusSeconds(60));
        metrics.refresh();
        assertThat(gauge("ldapportal.inventory.pending.approval.oldest.age.seconds")).isBetween(59.0, 90.0);
    }

    @Test
    void no_pending_approvals_reports_zero_backlog_age() {
        when(approvalRepo.findOldestCreatedAtByStatus(ApprovalStatus.PENDING)).thenReturn(null);
        metrics.refresh();
        assertThat(gauge("ldapportal.inventory.pending.approval.oldest.age.seconds")).isZero();
    }

    @Test
    void refresh_swallows_a_repository_failure_and_keeps_the_last_snapshot() {
        when(directoryRepo.count()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> metrics.refresh()).doesNotThrowAnyException();
        assertThat(gauge("ldapportal.inventory.directories")).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double role(String role) {
        return registry.get("ldapportal.inventory.admin.accounts").tag("role", role).gauge().value();
    }
}
