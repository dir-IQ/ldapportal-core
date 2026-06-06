// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.SyncSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic anti-entropy: drives {@link MembershipReconciler} across every enabled
 * sync set so the engine has an automatic backstop for missed events, drift left
 * by a transient target outage (re-driving FAILED identities), and orphans from
 * deletes the stream never saw. The stream is the fast path; this is the
 * consistency floor.
 *
 * <p>A single global cadence for Phase 1; per-sync-set {@code reconcileCadence}
 * scheduling lands with the config phase. Gated on {@code DIRECTORY_SYNC}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncReconcileScheduler {

    private final SyncSetRepository syncSetRepo;
    private final MembershipReconciler reconciler;
    private final EntitlementService entitlementService;

    @Scheduled(
            initialDelayString = "${ldapportal.sync.reconcile.initial-delay-ms:120000}",
            fixedDelayString = "${ldapportal.sync.reconcile.fixed-delay-ms:3600000}")
    public void reconcileAll() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            for (SyncSet set : syncSetRepo.findAllByEnabledTrue()) {
                try {
                    reconciler.reconcile(set.getId());
                } catch (Exception ex) {
                    log.error("Scheduled reconcile of sync set {} failed: {}", set.getId(), ex.toString());
                }
            }
        } catch (Exception ex) {
            // A @Scheduled method that throws stops being scheduled — guard it.
            log.error("Sync reconcile sweep failed: {}", ex.toString());
        }
    }
}
