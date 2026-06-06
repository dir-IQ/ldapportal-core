-- SPDX-License-Identifier: Apache-2.0
--
-- Phase 2 of the membership/sync engine:
--  * per-sync-set reconcile cadence (null => the global default cadence), with a
--    last-run stamp so the scheduler can compute "due" sets;
--  * a REVIEW membership state for brownfield quarantine — an identity whose
--    target correlation is ambiguous (multiple anchor matches, or an unanchored
--    collision at the placement DN) is held for an operator decision rather than
--    auto-overwritten.

ALTER TABLE sync_set
    ADD COLUMN reconcile_cadence_seconds BIGINT,
    ADD COLUMN reconcile_last_run_at     TIMESTAMPTZ;

ALTER TABLE membership DROP CONSTRAINT membership_state_check;
ALTER TABLE membership ADD CONSTRAINT membership_state_check
    CHECK (state IN ('APPLIED','PENDING','FAILED','REVIEW'));
