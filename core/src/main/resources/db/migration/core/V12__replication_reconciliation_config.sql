-- SPDX-License-Identifier: Apache-2.0
--
-- R-P0 of replication reconciliation: per-link configuration for periodic
-- comparison of a replication link's source vs target subtree.
-- See docs/plans/2026-05-31-replication-reconciliation-design.md §5.1.
--
-- Forward-only, additive. All columns default to "off / safe" so the
-- upgrade is a no-op for existing links — reconciliation stays opt-in.

ALTER TABLE replication_links
    -- Master switch; off by default so existing links are unaffected.
    ADD COLUMN reconcile_enabled       BOOLEAN     NOT NULL DEFAULT false,
    -- AUTO_CORRECT applies missing/drift findings immediately; REVIEW
    -- persists them as proposals for the operator. Review is the default.
    ADD COLUMN reconcile_mode          VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    -- Operator-chosen wall-clock start of the FIRST run; drives the initial
    -- reconcile_next_run_at.
    ADD COLUMN reconcile_first_run_at  TIMESTAMPTZ,
    -- Repeat cadence in seconds. Floor of 1 hour (3600 s), enforced below.
    ADD COLUMN reconcile_interval_secs INTEGER,
    -- Next due time the scheduler polls on; advanced by whole intervals on
    -- each completed run.
    ADD COLUMN reconcile_next_run_at   TIMESTAMPTZ,
    ADD COLUMN reconcile_last_run_at   TIMESTAMPTZ,
    -- How EXTRA_IN_TARGET (entry on target with no source counterpart) is
    -- handled. IGNORE: never flag. REVIEW: surface for a human even on an
    -- auto-correct link. AUTO: enqueue the DELETE automatically. Independent
    -- of reconcile_mode so the destructive class can be held back while
    -- adds/drift auto-apply. Review is the default.
    ADD COLUMN reconcile_delete_action VARCHAR(20) NOT NULL DEFAULT 'REVIEW',
    ADD CONSTRAINT replication_links_reconcile_mode_check
        CHECK (reconcile_mode IN ('AUTO_CORRECT','REVIEW')),
    ADD CONSTRAINT replication_links_reconcile_delete_action_check
        CHECK (reconcile_delete_action IN ('IGNORE','REVIEW','AUTO')),
    -- When enabled, the schedule fields must be populated and the interval
    -- must be at least 1 hour.
    ADD CONSTRAINT replication_links_reconcile_schedule_consistency
        CHECK (
          reconcile_enabled = false
          OR (reconcile_first_run_at IS NOT NULL
              AND reconcile_interval_secs IS NOT NULL
              AND reconcile_interval_secs >= 3600)
        );

-- Scheduler index: due, enabled links only. Partial keeps it tiny.
CREATE INDEX idx_replication_links_reconcile_due
    ON replication_links(reconcile_next_run_at)
    WHERE reconcile_enabled;

COMMENT ON COLUMN replication_links.reconcile_enabled IS
    'Opt-in master switch for periodic source↔target reconciliation.';
COMMENT ON COLUMN replication_links.reconcile_delete_action IS
    'IGNORE | REVIEW | AUTO — how an EXTRA_IN_TARGET entry is resolved, '
    'chosen independently of reconcile_mode because DELETE is irreversible.';
