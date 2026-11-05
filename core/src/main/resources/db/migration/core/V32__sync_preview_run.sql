-- SPDX-License-Identifier: Apache-2.0
--
-- The per-entry reconcile preview runs as a background job and stores its
-- result, so paging reads a fixed plan instead of re-scanning the source on
-- every page request.
--
-- sync_preview_run holds one row per sync set: the latest run. Starting a new
-- run is an atomic conditional UPDATE (the row is RUNNING with a fresh
-- heartbeat → refused), so at most one preview per set runs at a time across
-- app instances. run_id changes with every run; entries are keyed by it, so a
-- reader never mixes entries from two runs.
--
-- sync_preview_entry holds the run's planned entries in display order
-- (ordinal). "actionable" marks the entries the "changes only" view lists
-- (everything except UNCHANGED and NO_CHANGE). "changes" is the attribute
-- changes as JSON, with secret values already redacted.
--
-- Both are scratch data: a scheduled sweep removes runs older than the
-- configured retention, and deleting the set removes its run.

CREATE TABLE sync_preview_run (
    sync_set_id      UUID PRIMARY KEY REFERENCES sync_set(id) ON DELETE CASCADE,
    run_id           UUID NOT NULL,
    status           VARCHAR(16) NOT NULL,
    requested_by     TEXT,
    started_at       TIMESTAMPTZ NOT NULL,
    heartbeat_at     TIMESTAMPTZ NOT NULL,
    finished_at      TIMESTAMPTZ,
    processed        INTEGER NOT NULL DEFAULT 0,
    total            INTEGER NOT NULL DEFAULT 0,
    source_count     INTEGER NOT NULL DEFAULT 0,
    managed_count    BIGINT NOT NULL DEFAULT 0,
    actionable_count BIGINT NOT NULL DEFAULT 0,
    guard_tripped    BOOLEAN NOT NULL DEFAULT FALSE,
    guard_reason     TEXT,
    complete_scan    BOOLEAN NOT NULL DEFAULT FALSE,
    note             TEXT,
    error            TEXT,
    CONSTRAINT sync_preview_run_status_check CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED'))
);

CREATE TABLE sync_preview_entry (
    run_id             UUID NOT NULL,
    ordinal            INTEGER NOT NULL,
    sync_set_id        UUID NOT NULL REFERENCES sync_preview_run(sync_set_id) ON DELETE CASCADE,
    identity           TEXT NOT NULL,
    source_dn          TEXT,
    target_dn          TEXT,
    previous_target_dn TEXT,
    action             VARCHAR(16) NOT NULL,
    reason             TEXT,
    actionable         BOOLEAN NOT NULL,
    changes            TEXT,
    PRIMARY KEY (run_id, ordinal)
);

CREATE INDEX idx_sync_preview_entry_actionable ON sync_preview_entry (run_id, actionable, ordinal);
CREATE INDEX idx_sync_preview_entry_set ON sync_preview_entry (sync_set_id);
