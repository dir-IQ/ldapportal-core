-- SPDX-License-Identifier: Apache-2.0
-- R2: correlation id on audit events, for tracing every row emitted
-- while handling one top-level operation (API request, scheduler tick,
-- async replication dispatch).
--
-- The column is cheap and nullable (pre-R2 and changelog rows have none).
-- The partial index is the kill switch: it backs correlation pivots
-- (find every row sharing an id) but, if it ever dominates index size on
-- a busy install, it can be dropped with no functional impact —
--   DROP INDEX audit_events_correlation_id_idx;
-- and forensic pivots fall back to a sequential scan.
ALTER TABLE audit_events ADD COLUMN correlation_id UUID;

CREATE INDEX audit_events_correlation_id_idx
    ON audit_events (correlation_id)
    WHERE correlation_id IS NOT NULL;
