// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
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
    public record ClaimedPoll(ChangelogFormat format, String baseDn, Long cursor, ChangelogHealth health) {}

    /**
     * Acquire the single-flight poll lease for a link and, if won, return its
     * changelog config + cursor + current health. Empty when another tick/instance
     * holds the lease or the link is no longer an enabled CHANGELOG link.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedPoll> tryClaim(UUID linkId, OffsetDateTime now, OffsetDateTime staleCutoff) {
        if (linkRepo.claimChangelogPoll(linkId, now, staleCutoff) == 0) {
            return Optional.empty();
        }
        return linkRepo.findById(linkId)
                .filter(l -> l.getCaptureMode() == ReplicationCaptureMode.CHANGELOG)
                .map(l -> new ClaimedPoll(l.getChangelogFormat(), l.getChangelogBaseDn(),
                        l.getChangelogLastChangeNumber(), l.getChangelogHealth()));
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

    /** Flag links not polled since {@code threshold} as STALLED (§7A.7); returns the count. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markStalled(OffsetDateTime threshold) {
        return linkRepo.markStalledChangelogLinks(threshold);
    }

    /** First-run seed (only while the cursor is still null). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void seed(UUID linkId, long head, OffsetDateTime now) {
        linkRepo.seedChangelogCursor(linkId, head, now);
    }

    /** CAS cursor advance with computed health; returns true when this poller still owned the cursor. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean advance(UUID linkId, long expected, long newCursor, long head,
                           ChangelogHealth health, OffsetDateTime now) {
        return linkRepo.advanceChangelogCursor(linkId, expected, newCursor, head, health, now) == 1;
    }

    /** No new entries: refresh the observed head, health + poll timestamp. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(UUID linkId, long head, ChangelogHealth health, OffsetDateTime now) {
        linkRepo.recordChangelogPollObservation(linkId, head, health, now);
    }

    /** Gap recovery: CAS fast-forward the cursor past the trimmed span (§7A.1). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markGap(UUID linkId, long expected, long fastForward, long head, OffsetDateTime now) {
        return linkRepo.markChangelogGap(linkId, expected, fastForward, head, now) == 1;
    }

    /** Cursor-reset: flag CURSOR_RESET without advancing (§7A.2). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCursorReset(UUID linkId, long head, OffsetDateTime now) {
        linkRepo.markChangelogCursorReset(linkId, head, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(UUID linkId, String error, OffsetDateTime now) {
        linkRepo.recordChangelogPollError(linkId, truncate(error), now);
    }

    /** Disable the link after a non-self-healing config error (§7A.7). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void disableForConfigError(UUID linkId, String error, OffsetDateTime now) {
        linkRepo.disableChangelogForConfigError(linkId, truncate(error), now);
    }

    private static String truncate(String error) {
        return error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX));
    }
}
