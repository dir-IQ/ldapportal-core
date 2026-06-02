// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.repository.ReplicationLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fixed-delay sweep that fires reconciliation for links whose next-run is
 * due, plus a slower sweep that recovers runs abandoned by a crash. Mirrors
 * the {@code ReplicationWorker} pattern: thin, defensive, and idempotent —
 * the per-link single-flight guard lives in {@link ReconciliationTxOps}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationScheduler {

    private final ReplicationLinkRepository linkRepo;
    private final ReconciliationService     service;
    private final ReconciliationTxOps       txOps;
    private final EntitlementService        entitlementService;

    /** A run claimed longer ago than this is presumed abandoned. Default 30 min. */
    @Value("${ldapportal.reconciliation.run-timeout-ms:1800000}")
    private long runTimeoutMs;

    @Scheduled(fixedDelayString = "${ldapportal.reconciliation.sweep-ms:30000}")
    void sweep() {
        try {
            // Edition gate, mirroring ReplicationEnqueuer: when DIRECTORY_SYNC
            // isn't entitled (e.g. a commercial → community downgrade), the
            // autonomous path must not keep enqueuing corrective writes —
            // including deletes — against targets. The live capture path
            // already pauses; reconciliation pauses here too. (Null in any
            // direct-construction unit test → gate treated as open.)
            if (entitlementService != null
                    && !entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
                return;
            }
            OffsetDateTime now = OffsetDateTime.now();
            for (UUID linkId : linkRepo.findReconcileDueIds(now)) {
                try {
                    service.trigger(linkId, ReconciliationRunTrigger.SCHEDULED, null);
                } catch (RuntimeException ex) {
                    // One bad link must not stop the sweep for the others.
                    log.error("Failed to trigger reconciliation for link {}: {}", linkId, ex.toString());
                }
            }
        } catch (RuntimeException ex) {
            // A @Scheduled method that throws stops being rescheduled — swallow.
            log.error("Reconciliation sweep failed: {}", ex.toString());
        }
    }

    @Scheduled(fixedDelayString = "${ldapportal.reconciliation.stale-sweep-ms:120000}")
    void resetStale() {
        try {
            OffsetDateTime threshold = OffsetDateTime.now().minus(Duration.ofMillis(runTimeoutMs));
            int reset = txOps.resetStaleRuns(threshold);
            if (reset > 0) log.warn("Reset {} stale reconciliation run(s)", reset);
        } catch (RuntimeException ex) {
            log.error("Stale reconciliation sweep failed: {}", ex.toString());
        }
    }
}
