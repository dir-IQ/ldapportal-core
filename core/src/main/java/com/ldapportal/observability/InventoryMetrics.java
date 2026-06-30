// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.PendingApprovalRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deployment inventory gauges (Phase 3a observability): how big this install is
 * — directories, operator accounts, event subscribers, pending approvals.
 *
 * <p>This is <em>operational</em> data, not licensing — it ships in every
 * edition including community (an operator counting their own resources, scraped
 * locally). The license/quota overlay that pairs with these counts lives
 * separately and is dormant unless a license sets caps.</p>
 *
 * <p>Same DB-backed snapshot pattern as the Phase 2 metrics: a scheduled
 * {@link #refresh()} reads the repository counts into in-memory holders the
 * gauges read (decoupling scrape rate from DB load), with an eager
 * {@link #primeOnStartup() @PostConstruct prime} so the first scrape after a
 * restart is truthful. Refresh failures are swallowed — metrics never disrupt
 * the app.</p>
 */
@Component
@Slf4j
public class InventoryMetrics {

    private final DirectoryConnectionRepository directoryRepo;
    private final AccountRepository accountRepo;
    private final EventSubscriptionRepository subscriptionRepo;
    private final PendingApprovalRepository approvalRepo;

    private final AtomicLong directories = new AtomicLong();
    private final AtomicLong adminAccounts = new AtomicLong();
    private final AtomicLong superadminAccounts = new AtomicLong();
    private final AtomicLong eventSubscribers = new AtomicLong();
    private final AtomicLong pendingApprovals = new AtomicLong();
    private final AtomicLong pendingApprovalsOldestEpochSec = new AtomicLong();   // 0 = none pending

    public InventoryMetrics(DirectoryConnectionRepository directoryRepo,
                            AccountRepository accountRepo,
                            EventSubscriptionRepository subscriptionRepo,
                            PendingApprovalRepository approvalRepo,
                            MeterRegistry registry) {
        this.directoryRepo = directoryRepo;
        this.accountRepo = accountRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.approvalRepo = approvalRepo;
        bind(registry);
    }

    private void bind(MeterRegistry registry) {
        Gauge.builder("ldapportal.inventory.directories", directories, AtomicLong::doubleValue)
                .description("Configured directory connections")
                .baseUnit("directories").register(registry);
        Gauge.builder("ldapportal.inventory.admin.accounts", adminAccounts, AtomicLong::doubleValue)
                .description("Active operator accounts by role")
                .tag("role", "admin").baseUnit("accounts").register(registry);
        Gauge.builder("ldapportal.inventory.admin.accounts", superadminAccounts, AtomicLong::doubleValue)
                .description("Active operator accounts by role")
                .tag("role", "superadmin").baseUnit("accounts").register(registry);
        Gauge.builder("ldapportal.inventory.event.subscribers", eventSubscribers, AtomicLong::doubleValue)
                .description("Enabled event subscriptions")
                .baseUnit("subscribers").register(registry);
        Gauge.builder("ldapportal.inventory.pending.approvals", pendingApprovals, AtomicLong::doubleValue)
                .description("Approval requests awaiting action")
                .baseUnit("approvals").register(registry);
        Gauge.builder("ldapportal.inventory.pending.approval.oldest.age.seconds", pendingApprovalsOldestEpochSec,
                        f -> MetricAges.liveSeconds(f.get()))
                .description("Age of the oldest pending approval (approval backlog); 0 when none")
                .baseUnit("seconds").register(registry);
    }

    /**
     * Prime the snapshot at startup so the first scrape after a restart reports
     * real counts instead of zeros. {@link #refresh()} swallows its own failures.
     */
    @PostConstruct
    void primeOnStartup() {
        refresh();
    }

    @Scheduled(initialDelayString = "${ldapportal.metrics.refresh-ms:15000}",
               fixedDelayString = "${ldapportal.metrics.refresh-ms:15000}")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            directories.set(directoryRepo.count());
            adminAccounts.set(accountRepo.countByRoleAndActiveTrue(AccountRole.ADMIN));
            superadminAccounts.set(accountRepo.countByRoleAndActiveTrue(AccountRole.SUPERADMIN));
            eventSubscribers.set(subscriptionRepo.countByEnabledTrue());
            pendingApprovals.set(approvalRepo.countByStatus(ApprovalStatus.PENDING));
            OffsetDateTime oldestApproval = approvalRepo.findOldestCreatedAtByStatus(ApprovalStatus.PENDING);
            pendingApprovalsOldestEpochSec.set(oldestApproval == null ? 0L : oldestApproval.toEpochSecond());
        } catch (RuntimeException e) {
            // Metrics refresh must never disrupt the app; keep the last snapshot.
            log.debug("Inventory metrics refresh failed: {}", e.toString());
        }
    }
}
