// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.SyncSetRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Periodic anti-entropy: drives {@link MembershipReconciler} across enabled sync
 * sets that are <em>due</em>, so the engine has an automatic backstop for missed
 * events, drift left by a transient target outage (re-driving FAILED
 * identities), and orphans from deletes the stream never saw. The stream is the
 * fast path; this is the consistency floor.
 *
 * <p>Each tick reconciles sets whose {@code reconcile_last_run_at + cadence} has
 * elapsed, where cadence is the set's {@code reconcileCadenceSeconds} or the
 * global default. Gated on {@code DIRECTORY_SYNC}.
 */
@Component
@Slf4j
public class SyncReconcileScheduler {

    private final SyncSetRepository syncSetRepo;
    private final MembershipReconciler reconciler;
    private final SyncContentVerifier verifier;
    private final EntitlementService entitlementService;
    private final long defaultCadenceSeconds;

    public SyncReconcileScheduler(SyncSetRepository syncSetRepo,
                                  MembershipReconciler reconciler,
                                  SyncContentVerifier verifier,
                                  EntitlementService entitlementService,
                                  @Value("${ldapportal.sync.reconcile.default-cadence-seconds:3600}")
                                  long defaultCadenceSeconds) {
        this.syncSetRepo = syncSetRepo;
        this.reconciler = reconciler;
        this.verifier = verifier;
        this.entitlementService = entitlementService;
        this.defaultCadenceSeconds = defaultCadenceSeconds;
    }

    @Scheduled(
            initialDelayString = "${ldapportal.sync.reconcile.initial-delay-ms:120000}",
            fixedDelayString = "${ldapportal.sync.reconcile.tick-ms:60000}")
    public void reconcileDueSets() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            for (SyncSet set : syncSetRepo.findAllByEnabledTrue()) {
                if (!isDue(set, now)) {
                    continue;
                }
                try {
                    reconciler.reconcile(set.getId());
                    recordDrift(set.getId());
                } catch (Exception ex) {
                    log.error("Scheduled reconcile of sync set {} failed: {}", set.getId(), ex.toString());
                }
            }
        } catch (Exception ex) {
            // A @Scheduled method that throws stops being scheduled — guard it.
            log.error("Sync reconcile sweep failed: {}", ex.toString());
        }
    }

    /**
     * Refresh the cached content-verify snapshot so the dashboard can surface
     * reconciliation drift without re-reading both directories on every load.
     * Self-contained (own try/catch): an independent content verify re-reads
     * both sides and must never abort the reconcile sweep. Only a complete scan
     * is persisted — a partial enumeration would record misleadingly low drift.
     */
    private void recordDrift(UUID setId) {
        try {
            var v = verifier.verify(setId);
            if (v.sourceComplete() && v.targetComplete()) {
                syncSetRepo.recordVerifyResult(setId, OffsetDateTime.now(),
                        v.missingOnTarget(), v.orphanOnTarget(), v.contentMismatches());
            }
        } catch (Exception ex) {
            log.warn("Scheduled content verify of sync set {} failed: {}", setId, ex.toString());
        }
    }

    private boolean isDue(SyncSet set, OffsetDateTime now) {
        if (set.getReconcileLastRunAt() == null) {
            return true;
        }
        long cadence = set.getReconcileCadenceSeconds() != null
                ? set.getReconcileCadenceSeconds() : defaultCadenceSeconds;
        return !set.getReconcileLastRunAt().plus(Duration.ofSeconds(cadence)).isAfter(now);
    }
}
