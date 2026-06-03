// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import com.ldapportal.repository.ReplicationLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Short, committed transactions for the changelog poller — the DB-backed poll
 * lease and the cursor read/seed/advance. On a sibling bean so Spring's proxy
 * applies (the poller runs non-transactionally, like the worker). Each method
 * owns its own {@code REQUIRES_NEW} tx. Mirrors {@code ReconciliationTxOps}.
 *
 * <p>This is the {@code ChangelogCursorStore} + {@code ChangelogPollLease} seam
 * (design §6.1 / §8): the cursor is read and advanced through here only, so a
 * future int→cookie swap (AD DirSync) stays local.
 */
@Component
@RequiredArgsConstructor
public class ChangelogPollTxOps {

    private final ReplicationLinkRepository linkRepo;

    private static final int ERROR_MAX = 4000;

    /** What {@link #tryClaim} hands back so the poller runs without re-reading the link. */
    public record ClaimedPoll(ChangelogFormat format, String baseDn, Long cursor) {}

    /**
     * Acquire the single-flight poll lease for a link and, if won, return its
     * changelog config + cursor. Empty when another tick/instance holds the
     * lease or the link is no longer an enabled CHANGELOG link.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedPoll> tryClaim(UUID linkId, OffsetDateTime now, OffsetDateTime staleCutoff) {
        if (linkRepo.claimChangelogPoll(linkId, now, staleCutoff) == 0) {
            return Optional.empty();
        }
        return linkRepo.findById(linkId)
                .filter(l -> l.getCaptureMode() == ReplicationCaptureMode.CHANGELOG)
                .map(l -> new ClaimedPoll(
                        l.getChangelogFormat(), l.getChangelogBaseDn(), l.getChangelogLastChangeNumber()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID linkId) {
        linkRepo.releaseChangelogPoll(linkId);
    }

    /** Reclaim leases orphaned by a crash; returns the number released. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resetStaleLeases(OffsetDateTime threshold) {
        return linkRepo.resetStaleChangelogPolls(threshold);
    }

    /** First-run seed (only while the cursor is still null). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seed(UUID linkId, long head, OffsetDateTime now) {
        linkRepo.seedChangelogCursor(linkId, head, now);
    }

    /** CAS cursor advance; returns true when this poller still owned the cursor. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advance(UUID linkId, long expected, long newCursor, long head, OffsetDateTime now) {
        return linkRepo.advanceChangelogCursor(linkId, expected, newCursor, head, now) == 1;
    }

    /** No new entries: just refresh the observed head + poll timestamp. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(UUID linkId, long head, OffsetDateTime now) {
        linkRepo.recordChangelogPollObservation(linkId, head, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(UUID linkId, String error, OffsetDateTime now) {
        linkRepo.recordChangelogPollError(linkId,
                error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)), now);
    }
}
