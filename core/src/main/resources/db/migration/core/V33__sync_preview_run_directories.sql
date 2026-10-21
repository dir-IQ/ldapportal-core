-- SPDX-License-Identifier: Apache-2.0
--
-- The source and target directories a completed preview run planned against.
-- A preview is reported outdated once the set's link points elsewhere (or
-- either directory's connection settings are edited after the run started);
-- the link's own updated_at can't serve, since the changelog poller saves the
-- link on every poll.

ALTER TABLE sync_preview_run ADD COLUMN source_dir_id UUID;
ALTER TABLE sync_preview_run ADD COLUMN target_dir_id UUID;
