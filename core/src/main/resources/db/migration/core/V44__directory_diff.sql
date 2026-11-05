-- SPDX-License-Identifier: Apache-2.0
--
-- Directory diff: a superadmin compares two directories (or two branches of
-- one) entry by entry. A run records what was compared, its progress and the
-- count of entries in each group; its entries are one row each (a pair of
-- entries for SAME and DIFFERENT), with the per-attribute differences for
-- DIFFERENT. The directories have no foreign key: a run keeps their names and
-- outlives a deleted directory. Entries go with their run.

CREATE TABLE directory_diff_run (
    id                UUID PRIMARY KEY,
    requested_by      TEXT,
    status            VARCHAR(16) NOT NULL,
    phase             VARCHAR(16) NOT NULL,
    processed         INTEGER NOT NULL DEFAULT 0,
    total             INTEGER NOT NULL DEFAULT 0,
    current_dn        TEXT,
    error             TEXT,
    cancel_requested  BOOLEAN NOT NULL DEFAULT FALSE,
    started_at        TIMESTAMPTZ NOT NULL,
    heartbeat_at      TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    directory_a_id    UUID NOT NULL,
    directory_b_id    UUID NOT NULL,
    directory_a_name  TEXT,
    directory_b_name  TEXT,
    base_a            TEXT NOT NULL,
    base_b            TEXT NOT NULL,
    pairing           VARCHAR(16) NOT NULL,
    settings          TEXT NOT NULL,
    same_count        INTEGER NOT NULL DEFAULT 0,
    different_count   INTEGER NOT NULL DEFAULT 0,
    only_a_count      INTEGER NOT NULL DEFAULT 0,
    only_b_count      INTEGER NOT NULL DEFAULT 0,
    attribute_counts  TEXT,
    CONSTRAINT directory_diff_run_status_check
        CHECK (status IN ('RUNNING', 'COMPLETE', 'FAILED', 'CANCELLED')),
    CONSTRAINT directory_diff_run_pairing_check CHECK (pairing IN ('DN', 'ATTRIBUTE'))
);

CREATE INDEX idx_directory_diff_run_started ON directory_diff_run (started_at DESC);
CREATE INDEX idx_directory_diff_run_pair ON directory_diff_run (directory_a_id, directory_b_id, started_at DESC);

CREATE TABLE directory_diff_entry (
    run_id      UUID NOT NULL REFERENCES directory_diff_run(id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    category    VARCHAR(16) NOT NULL,
    dn_a        TEXT,
    dn_b        TEXT,
    attributes  TEXT,
    note        TEXT,
    detail      TEXT,
    PRIMARY KEY (run_id, seq),
    CONSTRAINT directory_diff_entry_category_check
        CHECK (category IN ('SAME', 'DIFFERENT', 'ONLY_A', 'ONLY_B'))
);

CREATE INDEX idx_directory_diff_entry_category ON directory_diff_entry (run_id, category, seq);
