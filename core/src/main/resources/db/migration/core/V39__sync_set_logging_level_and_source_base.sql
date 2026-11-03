-- Sync set editor rework.
--
-- 1. Run-history detail becomes a logging level. SUMMARY keeps each run's
--    counts and failure reasons only; DETAILED also keeps every changed or
--    flagged entry's DN (and the per-outcome samples). Sets that recorded
--    every DN become DETAILED; the rest become SUMMARY.
-- 2. The source base DN is required. A blank one meant "the source
--    directory's base DN", so existing blanks are filled in with exactly
--    that: behaviour doesn't change.

ALTER TABLE sync_set
    ADD COLUMN logging_level VARCHAR(16) NOT NULL DEFAULT 'SUMMARY';

UPDATE sync_set SET logging_level = 'DETAILED' WHERE record_all_dns;

ALTER TABLE sync_set
    ADD CONSTRAINT sync_set_logging_level_check CHECK (logging_level IN ('SUMMARY', 'DETAILED'));

ALTER TABLE sync_set DROP COLUMN record_all_dns;

UPDATE sync_set
   SET object_scope_base_dn = (
       SELECT d.base_dn
         FROM sync_links l
         JOIN directory_connections d ON d.id = l.source_dir_id
        WHERE l.id = sync_set.link_id)
 WHERE object_scope_base_dn IS NULL OR TRIM(object_scope_base_dn) = '';

ALTER TABLE sync_set ALTER COLUMN object_scope_base_dn SET NOT NULL;
