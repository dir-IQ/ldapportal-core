-- SPDX-License-Identifier: Apache-2.0
--
-- Why a reconcile's source entries and the set's managed entries differ, and
-- live progress for the sync set inventory's actions.
--
-- not_in_set / not_in_set_reasons: in-scope source entries the set doesn't
-- manage (they don't match the applicability filter, or can't be placed under
-- the target base), counted per reason with sample DNs (JSON).
--
-- phase / current_dn: where a running reconcile is (scanning the source,
-- applying entries, sweeping entries gone from the source) and the entry it's
-- on, written about every second while it runs; NULL once it finishes.
--
-- sync_verify_progress: the latest content verification of each set, as it
-- runs (reading the source, reading the target, comparing) and how it ended.
-- One row per set, replaced by each new verification.

ALTER TABLE sync_reconcile_run ADD COLUMN not_in_set INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sync_reconcile_run ADD COLUMN not_in_set_reasons TEXT;
ALTER TABLE sync_reconcile_run ADD COLUMN phase VARCHAR(16);
ALTER TABLE sync_reconcile_run ADD COLUMN current_dn TEXT;

CREATE TABLE sync_verify_progress (
    sync_set_id   UUID PRIMARY KEY REFERENCES sync_set(id) ON DELETE CASCADE,
    run_id        UUID NOT NULL,
    status        VARCHAR(16) NOT NULL,
    phase         VARCHAR(16) NOT NULL,
    processed     INTEGER NOT NULL DEFAULT 0,
    total         INTEGER NOT NULL DEFAULT 0,
    current_dn    TEXT,
    error         TEXT,
    started_at    TIMESTAMPTZ NOT NULL,
    heartbeat_at  TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT sync_verify_progress_status_check CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED'))
);
