-- SPDX-License-Identifier: Apache-2.0
--
-- A history of reconcile runs per sync set, so an operator can see what the
-- last reconcile actually did: when it ran, who or what started it, whether the
-- source scan completed, and how many entries it added, modified, moved,
-- deleted, held for review, failed or left alone.
--
-- A run writes its row when it starts (RUNNING) and refreshes heartbeat_at with
-- its counts as it goes; a RUNNING row whose heartbeat has gone stale belonged
-- to a server that stopped mid-run and reads as FAILED. Only the newest runs of
-- each set are kept, and deleting the set removes its history.

CREATE TABLE sync_reconcile_run (
    id              UUID PRIMARY KEY,
    sync_set_id     UUID NOT NULL REFERENCES sync_set(id) ON DELETE CASCADE,
    trigger_type    VARCHAR(16) NOT NULL,
    requested_by    TEXT,
    status          VARCHAR(16) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    heartbeat_at    TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    complete_scan   BOOLEAN NOT NULL DEFAULT FALSE,
    source_count    INTEGER NOT NULL DEFAULT 0,
    managed_before  BIGINT NOT NULL DEFAULT 0,
    managed_after   BIGINT,
    processed       INTEGER NOT NULL DEFAULT 0,
    added           INTEGER NOT NULL DEFAULT 0,
    modified        INTEGER NOT NULL DEFAULT 0,
    moved           INTEGER NOT NULL DEFAULT 0,
    deleted         INTEGER NOT NULL DEFAULT 0,
    held_for_review INTEGER NOT NULL DEFAULT 0,
    no_change       INTEGER NOT NULL DEFAULT 0,
    forgotten       INTEGER NOT NULL DEFAULT 0,
    unchanged       INTEGER NOT NULL DEFAULT 0,
    failed          INTEGER NOT NULL DEFAULT 0,
    faulted         INTEGER NOT NULL DEFAULT 0,
    skipped         INTEGER NOT NULL DEFAULT 0,
    guard_tripped   BOOLEAN NOT NULL DEFAULT FALSE,
    guard_reason    TEXT,
    error           TEXT,
    CONSTRAINT sync_reconcile_run_status_check CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED')),
    CONSTRAINT sync_reconcile_run_trigger_check CHECK (trigger_type IN ('SCHEDULED', 'MANUAL'))
);

CREATE INDEX idx_sync_reconcile_run_set_started ON sync_reconcile_run (sync_set_id, started_at DESC);
