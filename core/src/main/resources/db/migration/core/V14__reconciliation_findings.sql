-- SPDX-License-Identifier: Apache-2.0
--
-- R-P2 of replication reconciliation: persisted per-finding detail backing
-- the operator review + selective apply/dismiss UI.
-- See docs/plans/2026-05-31-replication-reconciliation-design.md §5.2.

CREATE TABLE reconciliation_findings (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    link_id       UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    finding_type  VARCHAR(30) NOT NULL,   -- MISSING_IN_TARGET | ATTRIBUTE_DRIFT | EXTRA_IN_TARGET
    suggested_op  VARCHAR(20) NOT NULL,   -- ADD | MODIFY | DELETE
    source_dn     VARCHAR(2000),          -- null for EXTRA_IN_TARGET
    target_dn     VARCHAR(2000) NOT NULL,
    -- The diff, ready to render and (minus UI-only keys) to encode as a
    -- replication_events payload. See the design §5.2 for the shape.
    detail        JSONB NOT NULL,
    status        VARCHAR(20) NOT NULL,   -- PROPOSED | AUTO_APPLIED | APPLIED | DISMISSED | SUPERSEDED
    -- Set when status -> AUTO_APPLIED / APPLIED; ties a finding to the
    -- corrective event the worker delivers.
    event_id      UUID REFERENCES replication_events(id) ON DELETE SET NULL,
    -- Operator account id (no FK — mirrors how audit stores actor ids).
    resolved_by   UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT reconciliation_findings_type_check
        CHECK (finding_type IN ('MISSING_IN_TARGET','ATTRIBUTE_DRIFT','EXTRA_IN_TARGET')),
    CONSTRAINT reconciliation_findings_op_check
        CHECK (suggested_op IN ('ADD','MODIFY','DELETE','MODIFY_DN')),
    CONSTRAINT reconciliation_findings_status_check
        CHECK (status IN ('PROPOSED','AUTO_APPLIED','APPLIED','DISMISSED','SUPERSEDED'))
);

-- Findings for a run, filtered by type in the review UI.
CREATE INDEX idx_reconciliation_findings_run
    ON reconciliation_findings(run_id, finding_type);

-- Open (PROPOSED) findings per link — backs the row badge / dashboard.
CREATE INDEX idx_reconciliation_findings_open
    ON reconciliation_findings(link_id) WHERE status = 'PROPOSED';

COMMENT ON TABLE reconciliation_findings IS
    'Per-finding detail from a reconciliation run; PROPOSED rows are the '
    'operator review queue, APPLIED/AUTO_APPLIED link to their corrective event.';
