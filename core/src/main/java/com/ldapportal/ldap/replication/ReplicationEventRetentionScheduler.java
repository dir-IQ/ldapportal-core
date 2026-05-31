// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.repository.ReplicationEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Nightly retention sweep for {@code replication_events} (R5). Two passes,
 * both keyed on cutoffs computed here and handed to portable JPQL deletes
 * on {@link ReplicationEventRepository}:
 *
 * <ul>
 *   <li><b>Floor</b> — delete {@code DELIVERED} events older than
 *       {@code floor-days} for enabled links. A successful delivery is
 *       audit-recorded separately; the event row is dispatch bookkeeping.</li>
 *   <li><b>Cap</b> — hard-delete <em>any</em> event enqueued more than
 *       {@code cap-days} ago, regardless of status. This deliberately
 *       catches {@code DEAD_LETTERED} / {@code SKIPPED} /
 *       {@code ACKNOWLEDGED} rows: an operator who hasn't triaged a dead
 *       letter in {@code cap-days} isn't going to, and the dead-lettering
 *       already persisted to the audit log.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> entitlement-gated: housekeeping must still
 * drain rows left behind after a commercial → community downgrade, when
 * {@code DIRECTORY_SYNC} is no longer entitled but old events remain.
 *
 * <p>Knobs (see {@code application.yml}):
 * {@code ldapportal.replication.retention.{floor-days,cap-days,cron}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationEventRetentionScheduler {

    private final ReplicationEventRepository eventRepo;

    @Value("${ldapportal.replication.retention.floor-days:30}")
    private int floorDays;

    @Value("${ldapportal.replication.retention.cap-days:90}")
    private int capDays;

    @Scheduled(cron = "${ldapportal.replication.retention.cron:0 30 2 * * *}")
    public void purge() {
        // A @Scheduled method that throws stops being rescheduled, so a
        // single bad run must never kill the nightly job. Each delete is
        // self-transactional (see the repository); we swallow + log here.
        try {
            OffsetDateTime now = OffsetDateTime.now();
            int floored = eventRepo.deleteDeliveredForEnabledLinksOlderThan(now.minusDays(floorDays));
            int capped  = eventRepo.deleteEnqueuedBefore(now.minusDays(capDays));
            if (floored > 0 || capped > 0) {
                log.info("Replication retention purge: removed {} delivered event(s) older than {}d "
                        + "on enabled links, {} event(s) past the {}d cap (any status)",
                        floored, floorDays, capped, capDays);
            }
        } catch (Exception ex) {
            log.error("Replication retention purge failed: {}", ex.getMessage(), ex);
        }
    }
}
