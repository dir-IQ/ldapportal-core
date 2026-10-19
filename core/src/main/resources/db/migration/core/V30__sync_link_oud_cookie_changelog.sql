-- SPDX-License-Identifier: Apache-2.0
--
-- Changelog capture for a sync link may read OUD's external changelog in cookie
-- mode. The cookie (persisted verbatim in changelog_cursor_token, already TEXT)
-- is replication-CSN based and valid on every replica, unlike the per-server
-- integer changeNumber of DSEE_CHANGELOG.

ALTER TABLE sync_links DROP CONSTRAINT IF EXISTS sync_links_changelog_format_check;

ALTER TABLE sync_links ADD CONSTRAINT sync_links_changelog_format_check
    CHECK (changelog_format IS NULL
           OR changelog_format IN ('DSEE_CHANGELOG', 'OUD_COOKIE_CHANGELOG'));
