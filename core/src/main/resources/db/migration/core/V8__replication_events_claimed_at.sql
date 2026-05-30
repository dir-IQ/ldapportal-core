-- SPDX-License-Identifier: Apache-2.0
--
-- Adds claimed_at to replication_events so the worker's stale-IN_FLIGHT
-- reset sweeps "how long ago was this row claimed" instead of "how long
-- ago was it enqueued". The previous predicate (enqueued_at < threshold)
-- false-resets any event older than 10 minutes the instant a worker
-- claims it, opening a double-delivery window for non-idempotent ops
-- (MODIFY add-value on a multi-valued attribute, MODIFY_DN).

ALTER TABLE replication_events
    ADD COLUMN claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN replication_events.claimed_at IS
    'Timestamp the row last transitioned to IN_FLIGHT, or NULL if it has '
    'never been claimed. Used by ReplicationWorker.resetStaleInFlight to '
    'recover events left in IN_FLIGHT by a crashed worker.';

-- Backfill existing IN_FLIGHT rows so the stale-reset sweep can pick
-- them up on first pass after upgrade. Use enqueued_at as the
-- conservative lower bound — any IN_FLIGHT row at upgrade time is
-- almost certainly already stale (the previous broken sweep would have
-- already reset anything fresh), so dating them to enqueued_at puts
-- them firmly past the 10-minute threshold and they get reset
-- immediately. PENDING / DELIVERED / FAILED / etc rows correctly stay
-- claimed_at = NULL since they're not currently held by a worker.
UPDATE replication_events
   SET claimed_at = enqueued_at
 WHERE status = 'IN_FLIGHT';
