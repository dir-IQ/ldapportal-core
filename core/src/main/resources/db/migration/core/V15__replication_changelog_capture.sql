-- SPDX-License-Identifier: Apache-2.0
--
-- C1 of changelog-driven replication: a second capture mode for a link.
-- Instead of intercepting source-side writes in-app (APP_INTERCEPT), a link
-- may poll the source directory's external changelog (cn=changelog on OUD)
-- and reconstruct each change into the existing replication_events queue.
-- See docs/plans/2026-06-03-changelog-replication-design.md §2.
--
-- Forward-only, additive. capture_mode defaults to APP_INTERCEPT so the
-- upgrade is a no-op for existing links — changelog capture is opt-in.

ALTER TABLE replication_links
    -- How changes are detected. APP_INTERCEPT (default) keeps the existing
    -- in-app wrapper; CHANGELOG polls the source changelog. Exclusive per
    -- link — flipping it changes HOW changes are detected, not WHAT.
    ADD COLUMN capture_mode                       VARCHAR(20)  NOT NULL DEFAULT 'APP_INTERCEPT',
    -- Changelog format the source exposes. v1 supports only DSEE_CHANGELOG
    -- (OUD cn=changelog); the CHECK widens when OpenLDAP/AD strategies land.
    ADD COLUMN changelog_format                   VARCHAR(25),
    -- Base DN of the changelog (sits outside the directory's baseDn); the
    -- service defaults this to 'cn=changelog' when blank.
    ADD COLUMN changelog_base_dn                  VARCHAR(500),
    -- Cursor / high-water mark: highest changeNumber already enqueued for
    -- this link. The poller searches changeNumber > cursor.
    ADD COLUMN changelog_last_change_number       BIGINT,
    -- ── scope: exclude filter (see §7B) ──
    -- Optional RFC 4515 filter; an entry within the replicated DIT that
    -- MATCHES this is excluded from replication entirely. Applies to both
    -- capture modes and reconciliation.
    ADD COLUMN exclude_filter                     VARCHAR(2000),
    -- ── liveness / health surfacing (see §7A) ──
    -- Last observed source head (lastChangeNumber); lag = this − cursor.
    ADD COLUMN changelog_source_last_change_number BIGINT,
    ADD COLUMN changelog_last_polled_at           TIMESTAMPTZ,
    ADD COLUMN changelog_last_error               TEXT,
    ADD COLUMN changelog_last_error_at            TIMESTAMPTZ,
    ADD COLUMN changelog_health                   VARCHAR(24)  NOT NULL DEFAULT 'HEALTHY',
    -- ── DB-backed single-flight lease for HA (mirrors ReconciliationTxOps) ──
    ADD COLUMN changelog_poll_claimed_at          TIMESTAMPTZ,
    ADD CONSTRAINT replication_links_capture_mode_check
        CHECK (capture_mode IN ('APP_INTERCEPT','CHANGELOG')),
    -- changelog_format constrained to the v1-supported value; widen when
    -- OpenLDAP/AD strategies land (mirrors the audit chk_changelog_format style).
    ADD CONSTRAINT replication_links_changelog_format_check
        CHECK (changelog_format IS NULL OR changelog_format IN ('DSEE_CHANGELOG')),
    -- changelog_health constrained to the known states (see §7A.7).
    ADD CONSTRAINT replication_links_changelog_health_check
        CHECK (changelog_health IN
            ('HEALTHY','LAGGING','STALLED','GAP_DETECTED','CURSOR_RESET','DISABLED_CONFIG_ERROR')),
    -- CHANGELOG mode requires its config; APP_INTERCEPT must leave it null.
    ADD CONSTRAINT replication_links_changelog_cfg_check
        CHECK (
          (capture_mode = 'APP_INTERCEPT' AND changelog_format IS NULL
                                           AND changelog_base_dn IS NULL)
          OR
          (capture_mode = 'CHANGELOG'     AND changelog_format IS NOT NULL
                                           AND changelog_base_dn IS NOT NULL)
        );

-- Real ordering/dedup key on the queue, set only for SOURCE_CHANGELOG rows.
-- Preferred over a JSONB functional index: smaller, faster, and it doubles as
-- the worker's FIFO tiebreak (§6.5 / review finding RF-2).
ALTER TABLE replication_events
    ADD COLUMN source_change_number BIGINT;

-- Exactly-once guard (§6.5): one queue row per (link, changeNumber) for
-- changelog-sourced events. Partial so APP_INTERCEPT / RECONCILIATION rows
-- (which carry no source change number) are unaffected.
CREATE UNIQUE INDEX replication_events_changelog_dedup
    ON replication_events (link_id, source_change_number)
    WHERE enqueue_source = 'SOURCE_CHANGELOG';

-- Poller sweep index: enabled changelog-capture links only. Partial keeps it tiny.
CREATE INDEX idx_replication_links_changelog_capture
    ON replication_links(id)
    WHERE enabled AND capture_mode = 'CHANGELOG';

COMMENT ON COLUMN replication_links.capture_mode IS
    'APP_INTERCEPT | CHANGELOG — how source changes are detected; exclusive per link.';
COMMENT ON COLUMN replication_links.changelog_last_change_number IS
    'Cursor: highest source changeNumber already enqueued for this link (high-water mark).';
COMMENT ON COLUMN replication_links.exclude_filter IS
    'Optional RFC 4515 filter; matching entries are excluded from replication entirely (both modes + reconciliation).';
COMMENT ON COLUMN replication_links.changelog_health IS
    'HEALTHY | LAGGING | STALLED | GAP_DETECTED | CURSOR_RESET | DISABLED_CONFIG_ERROR.';
COMMENT ON COLUMN replication_events.source_change_number IS
    'Source changeNumber for SOURCE_CHANGELOG rows; dedup key + worker FIFO tiebreak. NULL otherwise.';
