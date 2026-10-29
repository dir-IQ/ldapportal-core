-- The entry a running preview is dry-running now, so the progress line can
-- name it while a slow entry (a large group) is being checked. NULL before the
-- dry-run starts and once the run finishes.
ALTER TABLE sync_preview_run ADD COLUMN current_dn TEXT;
