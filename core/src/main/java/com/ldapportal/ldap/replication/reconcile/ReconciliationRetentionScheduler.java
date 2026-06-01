// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Nightly retention sweep for {@code reconciliation_runs} (R-P3). Mirrors
 * {@link com.ldapportal.ldap.replication.ReplicationEventRetentionScheduler}:
 * a single age-keyed bulk delete computed here and handed to a portable JPQL
 * delete on {@link ReconciliationRunRepository}.
 *
 * <p>Deletes finished runs older than {@code retention-days} <b>whose findings
 * are all resolved</b> — runs still holding a {@code PROPOSED} finding are
 * spared so the clock never discards pending operator review. The
 * {@code reconciliation_findings.run_id} FK is {@code ON DELETE CASCADE}, so a
 * deleted run takes its resolved findings with it; no separate finding sweep
 * is needed. Each apply/dismiss that clears the last open finding makes its
 * run eligible on the next nightly pass.
 *
 * <p>Deliberately <b>not</b> entitlement-gated — like the replication sweep,
 * housekeeping must still drain rows after a commercial → community downgrade
 * when {@code DIRECTORY_SYNC} is no longer entitled but old runs remain.
 *
 * <p>Knobs (see {@code application.yml}):
 * {@code ldapportal.reconciliation.retention.{days,cron}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationRetentionScheduler {

    private final ReconciliationRunRepository runRepo;

    @Value("${ldapportal.reconciliation.retention.days:90}")
    private int retentionDays;

    @Scheduled(cron = "${ldapportal.reconciliation.retention.cron:0 45 2 * * *}")
    public void purge() {
        // A @Scheduled method that throws stops being rescheduled, so one bad
        // run must never kill the nightly job. The delete is self-transactional
        // (see the repository); we swallow + log here.
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
            int removed = runRepo.deleteFinishedWithoutOpenFindingsBefore(cutoff);
            if (removed > 0) {
                log.info("Reconciliation retention purge: removed {} finished run(s) older than {}d "
                        + "(findings cascade-deleted; runs with open findings spared)",
                        removed, retentionDays);
            }
        } catch (Exception ex) {
            log.error("Reconciliation retention purge failed: {}", ex.getMessage(), ex);
        }
    }
}
