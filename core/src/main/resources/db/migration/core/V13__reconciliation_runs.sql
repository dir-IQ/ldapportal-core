-- SPDX-License-Identifier: Apache-2.0
--
-- R-P1 of replication reconciliation: run history + the corrective-event
-- provenance value. See docs/plans/2026-05-31-replication-reconciliation-design.md
-- §5.2 / §5.3.

-- Corrective events produced by reconciliation flow through the existing
-- replication_events queue and worker. Widen the provenance check to admit
-- the new source. Forward-only, additive.
ALTER TABLE replication_events
    DROP CONSTRAINT replication_events_enqueue_source_check,
    ADD  CONSTRAINT replication_events_enqueue_source_check
        CHECK (enqueue_source IN ('APP_INTERCEPT','SOURCE_CHANGELOG','RECONCILIATION'));

CREATE TABLE reconciliation_runs (
    id                 UUID PRIMARY KEY,
    link_id            UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    trigger            VARCHAR(20) NOT NULL,   -- SCHEDULED | MANUAL
    mode               VARCHAR(20) NOT NULL,   -- snapshot of reconcile_mode at run time
    status             VARCHAR(20) NOT NULL,   -- RUNNING | COMPLETED | FAILED | CANCELLED
    -- Single-flight claim timestamp; mirrors replication_events.claimed_at.
    -- Used by the stale-run sweep to recover runs left RUNNING by a crash.
    claimed_at         TIMESTAMPTZ,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    source_entry_count INTEGER,
    target_entry_count INTEGER,
    missing_count      INTEGER NOT NULL DEFAULT 0,   -- MISSING_IN_TARGET
    drift_count        INTEGER NOT NULL DEFAULT 0,   -- ATTRIBUTE_DRIFT
    extra_count        INTEGER NOT NULL DEFAULT 0,   -- EXTRA_IN_TARGET
    suppressed_count   INTEGER NOT NULL DEFAULT 0,   -- shadowed by a live event
    applied_count      INTEGER NOT NULL DEFAULT 0,   -- corrective events enqueued (auto)
    error              TEXT,
    CONSTRAINT reconciliation_runs_status_check
        CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT reconciliation_runs_trigger_check
        CHECK (trigger IN ('SCHEDULED','MANUAL')),
    CONSTRAINT reconciliation_runs_mode_check
        CHECK (mode IN ('AUTO_CORRECT','REVIEW'))
);

CREATE INDEX idx_reconciliation_runs_link
    ON reconciliation_runs(link_id, started_at DESC);

-- At most one live run per link — the single-flight guard the scheduler
-- relies on (INSERT of a second RUNNING row for the same link fails).
CREATE UNIQUE INDEX uq_reconciliation_runs_one_active
    ON reconciliation_runs(link_id) WHERE status = 'RUNNING';

COMMENT ON TABLE reconciliation_runs IS
    'History of source↔target reconciliation runs per replication link. '
    'Counts summarise the discrepancies found; corrective actions ride the '
    'replication_events queue.';
