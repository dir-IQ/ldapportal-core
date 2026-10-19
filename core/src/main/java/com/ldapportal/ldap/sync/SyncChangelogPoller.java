// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.ldap.changelog.OudCookieChangelogStrategy;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Changelog-capture adapter: polls each CHANGELOG-mode sync link's source
 * changelog and emits {@code recompute(targetDN)} per change record — including
 * DELETE records (the engine re-reads the source → absent → OUT → deletes via
 * the index, needing no source attrs). This is the design's key simplification:
 * the changelog's lossy {@code changes} blob is ignored, so there is no
 * exactly-once dedup, no per-link FIFO, and no LDIF reconstruction — convergence
 * makes a bare "this DN changed" signal sufficient.
 *
 * <p>Keeps the operational machinery: an HA poll-lease, a cursor, lag/gap/cursor-
 * reset health, and a reconcile request when the cursor falls off the bottom of
 * the changelog. The poller never reconciles inline: it marks the link's sets due
 * ({@link SyncSetRepository#requestReconcile}) and moves the cursor on, so a large
 * or failing reconcile can't block the poll thread or hold the cursor back; the
 * reconcile scheduler runs them on its next tick. Gated on {@code DIRECTORY_SYNC}.
 *
 * <p>Two cursor families: the DSEE integer {@code changeNumber} (per-server, so
 * only meaningful against the node that issued it) and the OUD external
 * changelog cookie (replication-CSN based, valid on any replica — see
 * {@link #pollCookie}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncChangelogPoller {

    private static final int BATCH = 500;
    /**
     * A pass keeps reading pages while the source has more, up to these bounds, so
     * a busy source doesn't fall behind at one page per poll interval.
     */
    private static final int MAX_PAGES_PER_PASS = 20;
    private static final Duration PASS_BUDGET = Duration.ofSeconds(5);
    private static final long LAG_THRESHOLD = 1000;
    private static final Duration STALE_LEASE = Duration.ofMinutes(5);
    /** Minimum spacing between refusal-driven re-seeds (each one requests a reconcile of every set). */
    private static final Duration RESEED_BACKOFF = Duration.ofHours(1);
    private static final String COOKIE_REFUSED_AGAIN = "changelog cookie refused again after re-seed: ";
    private static final DseeChangelogStrategy DSEE = new DseeChangelogStrategy();
    private static final OudCookieChangelogStrategy OUD_COOKIE = new OudCookieChangelogStrategy();
    private static final String NO_PROGRESS = "changelog search was cut short by a server limit before "
            + "returning any change it could resume from; check the bind account's size, time and "
            + "lookthrough limits on the source";
    /** Search cut-offs whose partial page is still an in-order prefix of the change stream. */
    private static final Set<ResultCode> PARTIAL_PAGE_CODES = Set.of(
            ResultCode.SIZE_LIMIT_EXCEEDED, ResultCode.TIME_LIMIT_EXCEEDED, ResultCode.ADMIN_LIMIT_EXCEEDED);

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository syncSetRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final LdapConnectionFactory connectionFactory;
    private final RecomputeEnqueuer enqueuer;
    private final EntitlementService entitlementService;

    @Scheduled(fixedDelayString = "${ldapportal.sync.changelog.fixed-delay-ms:15000}")
    public void poll() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime staleBefore = now.minus(STALE_LEASE);
            for (UUID linkId : linkRepo.findChangelogCaptureLinkIds()) {
                if (linkRepo.claimChangelogPoll(linkId, now, staleBefore) == 0) {
                    continue; // another instance holds the lease
                }
                // One link's failure (e.g. an optimistic-lock conflict with an admin
                // edit saving its state) must not skip the rest of the pass. Its
                // lease lapses after STALE_LEASE and it is polled again.
                try {
                    pollOne(linkId);
                } catch (Exception ex) {
                    log.error("Sync changelog poll of link {} failed: {}", linkId, ex.toString());
                }
            }
        } catch (Exception ex) {
            log.error("Sync changelog poll pass failed: {}", ex.toString());
        }
    }

    /** Poll one link (visible for tests; the scheduled path gates + leases first). */
    void pollOne(UUID linkId) {
        SyncLink link = linkRepo.findById(linkId).orElse(null);
        if (link == null) {
            return;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        ChangelogStrategy strategy = strategyFor(link.getChangelogFormat());
        if (source == null || strategy == null) {
            markError(link, "source directory or changelog strategy unavailable",
                    SyncChangelogHealth.DISABLED_CONFIG_ERROR);
            return;
        }
        List<SyncSet> sets = syncSetRepo.findAllByLinkId(linkId).stream().filter(SyncSet::isEnabled).toList();
        if (strategy == OUD_COOKIE) {
            pollCookie(link, source, sets);
            return;
        }
        try {
            PollResult r = connectionFactory.withConnectionUnreplicated(source, conn -> {
                RootDSE root = conn.getRootDSE();
                Long firstCn = root == null ? null : root.getAttributeValueAsLong("firstChangeNumber");
                Long lastCn = root == null ? null : root.getAttributeValueAsLong("lastChangeNumber");
                // Canonical cursor is the opaque token; the DSEE family reads it as
                // a changeNumber. Fall back to the numeric mirror for pre-V5 rows.
                Long cursor = SyncChangelogCursor.toChangeNumber(link.getChangelogCursorToken());
                if (cursor == null) {
                    cursor = link.getChangelogLastChangeNumber();
                }

                boolean cursorReset = cursor != null && lastCn != null && lastCn < cursor;
                if (cursorReset) {
                    cursor = 0L;
                }
                boolean gap = cursor != null && firstCn != null && firstCn > cursor + 1;

                long maxCn = cursor != null ? cursor : 0L;
                Long after = cursor;
                long deadline = System.nanoTime() + PASS_BUDGET.toNanos();
                int pages = 0;
                boolean more;
                do {
                    ChangelogReadContext ctx = new ChangelogReadContext(link.getChangelogBaseDn(), null, after);
                    Page page = searchPage(conn, strategy.buildSearchRequest(ctx, BATCH));
                    long before = maxCn;
                    for (SearchResultEntry e : page.entries()) {
                        String id = strategy.extractEntryId(e);
                        String dn = strategy.extractTargetDn(e);
                        if (id == null || dn == null) {
                            continue;
                        }
                        long cn = Long.parseLong(id);
                        enqueueChanged(sets, dn, strategy.extractPostModifyDn(e).orElse(null), cn);
                        maxCn = Math.max(maxCn, cn);
                    }
                    pages++;
                    more = page.truncated() || page.entries().size() >= BATCH;
                    if (more && maxCn == before) {
                        if (pages == 1) {
                            throw new IllegalStateException(NO_PROGRESS);
                        }
                        break; // keep this pass's progress; the next pass reports the stall
                    }
                    after = maxCn;
                    // On a gap the cursor jumps to the head anyway; no point paging on.
                } while (more && !gap && pages < MAX_PAGES_PER_PASS && System.nanoTime() < deadline);
                return new PollResult(maxCn, lastCn, gap, cursorReset);
            });

            if (r.gap()) {
                log.warn("Sync link {}: changelog gap detected; requesting reconcile to re-derive state", linkId);
                syncSetRepo.requestReconcile(linkId);
            }
            advance(link, r);
        } catch (Exception ex) {
            markError(link, ex.getMessage(), SyncChangelogHealth.STALLED);
        }
    }

    private void advance(SyncLink link, PollResult r) {
        long newCursor = r.gap() && r.sourceHead() != null ? r.sourceHead() : r.maxChangeNumber();
        link.setChangelogCursorToken(SyncChangelogCursor.fromChangeNumber(newCursor));
        link.setChangelogLastChangeNumber(newCursor);
        link.setChangelogSourceLastChangeNumber(r.sourceHead());
        link.setChangelogLastPolledAt(OffsetDateTime.now());
        link.setChangelogLastError(null);
        link.setChangelogPollClaimedAt(null);
        SyncChangelogHealth health = SyncChangelogHealth.HEALTHY;
        if (r.cursorReset()) {
            health = SyncChangelogHealth.CURSOR_RESET;
        } else if (r.gap()) {
            health = SyncChangelogHealth.GAP_DETECTED;
        } else if (r.sourceHead() != null && r.sourceHead() - newCursor > LAG_THRESHOLD) {
            health = SyncChangelogHealth.LAGGING;
        }
        link.setChangelogHealth(health);
        linkRepo.save(link);
    }

    /**
     * Cookie-mode poll. The cursor token is the OUD {@code changeLogCookie},
     * passed back verbatim; it advances to the cookie on the last entry read.
     * No numeric head exists, so:
     * <ul>
     *   <li><b>No cursor yet</b> — seed: start from the source's
     *       {@code lastExternalChangelogCookie} and request a reconcile of every
     *       set (nothing before the head is known to have been seen). The head is
     *       read before the request, so the reconcile covers everything older.</li>
     *   <li><b>Cookie refused</b> ({@code UNWILLING_TO_PERFORM}: purged past it, or
     *       an unknown domain) — the gap path: re-seed from the head and request a
     *       reconcile. A refusal of a cookie re-seeded under {@link #RESEED_BACKOFF}
     *       ago is not a gap (the cookie is fresh) but a server problem, so it
     *       stalls instead of requesting a reconcile on every poll.</li>
     *   <li><b>Paging</b> — a full or cut-short page means more is pending; the
     *       pass keeps reading (bounded by {@link #MAX_PAGES_PER_PASS} and
     *       {@link #PASS_BUDGET}) and reports {@code LAGGING} if it stops with
     *       more still pending. A page that makes no progress is {@code STALLED}.</li>
     * </ul>
     */
    private void pollCookie(SyncLink link, DirectoryConnection source, List<SyncSet> sets) {
        String cursor = link.getChangelogCursorToken();
        boolean seed = cursor == null || cursor.isBlank();
        try {
            CookiePollResult r = connectionFactory.withConnectionUnreplicated(source, conn -> {
                if (seed) {
                    return CookiePollResult.reseed(headCookie(conn));
                }
                String position = cursor;
                long deadline = System.nanoTime() + PASS_BUDGET.toNanos();
                int pages = 0;
                boolean more;
                do {
                    SearchRequest req = OUD_COOKIE.buildCookieSearchRequest(link.getChangelogBaseDn(), position, BATCH);
                    Page page;
                    try {
                        // A cut-short page is still a prefix of the change stream;
                        // the last entry's cookie is a valid resume point.
                        page = searchPage(conn, req);
                    } catch (LDAPSearchException ex) {
                        if (ex.getResultCode() != ResultCode.UNWILLING_TO_PERFORM) {
                            throw ex;
                        }
                        if (pages == 0) {
                            return CookiePollResult.refused(headCookie(conn), ex.getDiagnosticMessage());
                        }
                        // A cookie the server issued in this same pass: keep the
                        // progress made, and let the next pass face the refusal.
                        return CookiePollResult.advanced(position, true);
                    }
                    String before = position;
                    for (SearchResultEntry e : page.entries()) {
                        String cookie = OUD_COOKIE.extractEntryId(e);
                        String dn = OUD_COOKIE.extractTargetDn(e);
                        if (dn != null) {
                            enqueueChanged(sets, dn, OUD_COOKIE.extractPostModifyDn(e).orElse(null), null);
                        }
                        if (cookie != null) {
                            position = cookie;
                        }
                    }
                    pages++;
                    more = page.truncated() || page.entries().size() >= BATCH;
                    if (more && Objects.equals(position, before)) {
                        if (pages == 1) {
                            throw new IllegalStateException(NO_PROGRESS);
                        }
                        break; // keep this pass's progress; the next pass reports the stall
                    }
                } while (more && pages < MAX_PAGES_PER_PASS && System.nanoTime() < deadline);
                return CookiePollResult.advanced(position, more);
            });

            if (r.reseed()) {
                if (r.head() == null) {
                    markError(link, "source Root DSE returned no " + OudCookieChangelogStrategy.ROOT_DSE_LAST_COOKIE
                            + " (external changelog not enabled, no replicated suffix on the source, "
                            + "or the bind account cannot read it)",
                            SyncChangelogHealth.DISABLED_CONFIG_ERROR);
                    return;
                }
                if (r.refusal() != null && reseededRecently(link)) {
                    markError(link, COOKIE_REFUSED_AGAIN + r.refusal(), SyncChangelogHealth.STALLED);
                    return;
                }
                if (r.refusal() != null) {
                    log.warn("Sync link {}: changelog cookie refused ({}); requesting reconcile to re-derive state",
                            link.getId(), r.refusal());
                }
                syncSetRepo.requestReconcile(link.getId());
                advanceCookie(link, r.head(),
                        r.refusal() != null ? SyncChangelogHealth.GAP_DETECTED : SyncChangelogHealth.HEALTHY);
            } else {
                advanceCookie(link, r.cursor(), r.more() ? SyncChangelogHealth.LAGGING : SyncChangelogHealth.HEALTHY);
            }
        } catch (Exception ex) {
            markError(link, ex.getMessage(), SyncChangelogHealth.STALLED);
        }
    }

    /**
     * Whether the current cursor came from a refusal-driven re-seed within the
     * backoff window and nothing has been read since: still GAP_DETECTED, or
     * already stalled by a repeat refusal. {@code markError} leaves
     * {@code lastPolledAt} alone, so it still dates the re-seed.
     */
    private static boolean reseededRecently(SyncLink link) {
        SyncChangelogHealth h = link.getChangelogHealth();
        boolean afterReseed = h == SyncChangelogHealth.GAP_DETECTED
                || (h == SyncChangelogHealth.STALLED && link.getChangelogLastError() != null
                        && link.getChangelogLastError().startsWith(COOKIE_REFUSED_AGAIN));
        OffsetDateTime at = link.getChangelogLastPolledAt();
        return afterReseed && at != null && at.isAfter(OffsetDateTime.now().minus(RESEED_BACKOFF));
    }

    /**
     * Run a changelog search, keeping the partial page when the server stops at a
     * size, time or lookthrough limit (ours or its own per-user limits). UnboundID
     * reports those as an exception carrying the entries already returned, which
     * arrive in changelog order; dropping them would leave the cursor stuck for as
     * long as the backlog exceeds what one search can return.
     */
    private static Page searchPage(FullLDAPInterface conn, SearchRequest req) throws LDAPSearchException {
        try {
            return new Page(conn.search(req).getSearchEntries(), false);
        } catch (LDAPSearchException ex) {
            if (PARTIAL_PAGE_CODES.contains(ex.getResultCode())) {
                return new Page(ex.getSearchEntries(), true);
            }
            throw ex;
        }
    }

    /** The source's newest cookie, requested by name (it is a virtual operational attribute). */
    private static String headCookie(FullLDAPInterface conn) throws LDAPException {
        SearchResultEntry root = conn.getEntry("", OudCookieChangelogStrategy.ROOT_DSE_LAST_COOKIE);
        String head = root == null ? null
                : root.getAttributeValue(OudCookieChangelogStrategy.ROOT_DSE_LAST_COOKIE);
        return head == null || head.isBlank() ? null : head;
    }

    private void advanceCookie(SyncLink link, String cursor, SyncChangelogHealth health) {
        link.setChangelogCursorToken(cursor);
        // No monotonic integer in cookie mode; the numeric mirrors stay empty.
        link.setChangelogLastChangeNumber(null);
        link.setChangelogSourceLastChangeNumber(null);
        link.setChangelogLastPolledAt(OffsetDateTime.now());
        link.setChangelogLastError(null);
        link.setChangelogPollClaimedAt(null);
        link.setChangelogHealth(health);
        linkRepo.save(link);
    }

    /**
     * Enqueue a change record's DN(s) for every set whose scope covers them. A
     * rename/move record names the PRE-move DN; the post-move DN is enqueued as
     * well so a move INTO a set's scope is seen (the old DN alone never passes
     * that set's scope check), and a move OUT of scope still reaches the set
     * through the old DN.
     */
    private void enqueueChanged(List<SyncSet> sets, String dn, String postMoveDn, Long srcCursor) {
        List<String> changedDns = postMoveDn != null ? List.of(dn, postMoveDn) : List.of(dn);
        for (SyncSet set : sets) {
            for (String changed : changedDns) {
                if (SyncScopes.inScope(set, changed)) {
                    enqueuer.enqueue(set.getId(), changed, srcCursor);
                }
            }
        }
    }

    private void markError(SyncLink link, String message, SyncChangelogHealth health) {
        link.setChangelogLastError(message);
        link.setChangelogLastErrorAt(OffsetDateTime.now());
        link.setChangelogHealth(health);
        link.setChangelogPollClaimedAt(null);
        linkRepo.save(link);
        log.warn("Sync link {}: changelog poll error ({}): {}", link.getId(), health, message);
    }

    private static ChangelogStrategy strategyFor(ChangelogFormat format) {
        if (format == null) {
            return null;
        }
        return switch (format) {
            case DSEE_CHANGELOG -> DSEE;
            case OUD_COOKIE_CHANGELOG -> OUD_COOKIE;
            default -> null;
        };
    }

    private record PollResult(long maxChangeNumber, Long sourceHead, boolean gap, boolean cursorReset) {
    }

    /** One changelog search page; {@code truncated} when the server cut the search short at a limit. */
    private record Page(List<SearchResultEntry> entries, boolean truncated) {
    }

    /**
     * Outcome of one cookie poll: either an advance ({@code cursor}, {@code more})
     * or a re-seed from {@code head} — first poll, or {@code refusal} set when the
     * server refused the stored cookie.
     */
    private record CookiePollResult(String cursor, boolean more, boolean reseed, String head, String refusal) {
        static CookiePollResult advanced(String cursor, boolean more) {
            return new CookiePollResult(cursor, more, false, null, null);
        }

        static CookiePollResult reseed(String head) {
            return new CookiePollResult(null, false, true, head, null);
        }

        static CookiePollResult refused(String head, String diagnostic) {
            return new CookiePollResult(null, false, true, head,
                    diagnostic == null || diagnostic.isBlank() ? "UNWILLING_TO_PERFORM" : diagnostic);
        }
    }
}
