-- SPDX-License-Identifier: Apache-2.0
--
-- Phase 3 of the membership/sync engine: changelog-capture mode for a link. A
-- link may poll the source directory's external changelog (cn=changelog) instead
-- of intercepting in-app writes; the poller emits recompute(targetDN) per change
-- record (the engine re-reads the source, so no LDIF reconstruction / exactly-
-- once machinery is needed). Cursor + health + an HA poll-lease are tracked here.

ALTER TABLE sync_links
    ADD COLUMN changelog_format                    VARCHAR(25),
    ADD COLUMN changelog_base_dn                   VARCHAR(500),
    ADD COLUMN changelog_last_change_number        BIGINT,
    ADD COLUMN changelog_source_last_change_number BIGINT,
    ADD COLUMN changelog_last_polled_at            TIMESTAMPTZ,
    ADD COLUMN changelog_last_error                TEXT,
    ADD COLUMN changelog_last_error_at             TIMESTAMPTZ,
    ADD COLUMN changelog_health                    VARCHAR(24) NOT NULL DEFAULT 'HEALTHY',
    ADD COLUMN changelog_poll_claimed_at           TIMESTAMPTZ,
    ADD CONSTRAINT sync_links_changelog_format_check
        CHECK (changelog_format IS NULL OR changelog_format IN ('DSEE_CHANGELOG')),
    ADD CONSTRAINT sync_links_changelog_health_check
        CHECK (changelog_health IN
            ('HEALTHY','LAGGING','STALLED','GAP_DETECTED','CURSOR_RESET','DISABLED_CONFIG_ERROR')),
    ADD CONSTRAINT sync_links_changelog_cfg_check
        CHECK (
          (capture_mode = 'APP_INTERCEPT' AND changelog_format IS NULL AND changelog_base_dn IS NULL)
          OR
          (capture_mode = 'CHANGELOG'     AND changelog_format IS NOT NULL AND changelog_base_dn IS NOT NULL)
        );

CREATE INDEX idx_sync_links_changelog_capture
    ON sync_links(id) WHERE enabled AND capture_mode = 'CHANGELOG';
