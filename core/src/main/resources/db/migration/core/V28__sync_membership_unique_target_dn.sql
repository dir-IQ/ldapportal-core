-- One target entry is owned by at most one identity per sync set.
--
-- Before this, a source entry deleted and re-created at the same DN (a new
-- entryUUID) left two index rows pointing at one target DN; the reconcile
-- not-seen sweep then deleted the live target entry through the stale row.
-- Collapse any existing duplicates first, keeping the most recently written
-- row (highest version, then identity as a stable tie-break). The dropped rows
-- are index memory only: the next reconcile re-derives them from the source.
DELETE FROM sync_membership m
WHERE EXISTS (
    SELECT 1 FROM sync_membership o
    WHERE o.sync_set_id = m.sync_set_id
      AND o.target_dn = m.target_dn
      AND o.identity <> m.identity
      AND (o.version > m.version OR (o.version = m.version AND o.identity > m.identity))
);

CREATE UNIQUE INDEX uq_sync_membership_target ON sync_membership (sync_set_id, target_dn);
