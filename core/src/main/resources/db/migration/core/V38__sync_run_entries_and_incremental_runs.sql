-- Full per-run DN list (opt-in per sync set), and incremental runs: the
-- changes the recompute worker applies between reconciles (changelog,
-- in-app edits, closure fan-out) are recorded in the run history too.

ALTER TABLE sync_set
    ADD COLUMN record_all_dns BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sync_reconcile_run
    ADD COLUMN all_dns_recorded BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sync_reconcile_run DROP CONSTRAINT sync_reconcile_run_trigger_check;
ALTER TABLE sync_reconcile_run
    ADD CONSTRAINT sync_reconcile_run_trigger_check
        CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'INCREMENTAL'));

-- Every entry a run changed or flagged (every outcome but UNCHANGED), in
-- processing order. Written only while the set's record_all_dns is on; goes
-- with its run (history pruning and set deletion cascade here).
CREATE TABLE sync_reconcile_run_entry (
    run_id     UUID NOT NULL REFERENCES sync_reconcile_run(id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,
    outcome    VARCHAR(16) NOT NULL,
    target_dn  TEXT NOT NULL,
    reason     TEXT,
    PRIMARY KEY (run_id, seq)
);
