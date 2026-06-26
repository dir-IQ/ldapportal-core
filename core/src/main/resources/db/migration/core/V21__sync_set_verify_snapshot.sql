-- SPDX-License-Identifier: Apache-2.0
--
-- Persisted content-verification snapshot per sync set. The independent content
-- verify (SyncContentVerifier) re-reads both directories and is far too expensive
-- to run on every dashboard load, so its last result is cached here: the
-- scheduled reconcile pass and an operator-triggered verify both refresh it.
-- The dashboard reads these columns to surface reconciliation drift
-- (RECONCILIATION_DRIFT_OPEN) cheaply. Null counts => the set has never been
-- verified, so no drift is claimed.

ALTER TABLE sync_set
    ADD COLUMN last_verified_at      TIMESTAMPTZ,
    ADD COLUMN verify_missing_count  INTEGER,
    ADD COLUMN verify_orphan_count   INTEGER,
    ADD COLUMN verify_mismatch_count INTEGER;
