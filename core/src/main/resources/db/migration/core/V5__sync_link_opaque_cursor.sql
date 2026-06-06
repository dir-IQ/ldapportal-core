-- SPDX-License-Identifier: Apache-2.0
--
-- Phase 4 of the membership/sync engine: generalize the changelog cursor from an
-- integer changeNumber to an opaque token, so heterogeneous feeds whose cursor
-- isn't a monotonic integer (AD DirSync cookie, syncrepl cookie, Entra delta
-- link) can persist their position in the same place. For the DSEE numeric
-- family the token is the decimal changeNumber as text; the numeric column is
-- retained as a per-format observability/lag mirror.

ALTER TABLE sync_links
    ADD COLUMN changelog_cursor_token TEXT;

-- Backfill the token from the existing numeric cursor for any DSEE links already
-- polling, so the cursor survives the generalization.
UPDATE sync_links
   SET changelog_cursor_token = CAST(changelog_last_change_number AS TEXT)
 WHERE changelog_last_change_number IS NOT NULL;
