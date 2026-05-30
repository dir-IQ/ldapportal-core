-- SPDX-License-Identifier: Apache-2.0
--
-- Adds claimed_at to event_outbox so the dispatcher's stale-DELIVERING
-- reset sweeps "how long ago was this row claimed" instead of "how long
-- ago was its next_attempt_at scheduled". The previous predicate
-- (next_attempt_at < cutoff) false-resets any backlog-drain claim the
-- instant it lands: a row whose next_attempt_at sits far in the past
-- (long backoff that just elapsed, or worker downtime) flips back to
-- PENDING within the 60s sweep tick while the dispatcher's HTTP POST
-- is still in flight. That opens a double-delivery window — the
-- replication subsystem's stale-reset bug, exact same shape, fixed
-- here with the exact same template (see V8).

ALTER TABLE event_outbox
    ADD COLUMN claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN event_outbox.claimed_at IS
    'Timestamp the row last transitioned to DELIVERING, or NULL if it has '
    'never been claimed. Used by OutboundDispatcherScheduler.resetStaleDelivering '
    'to recover rows left in DELIVERING by a crashed dispatcher.';

-- Replace the old partial index on next_attempt_at (no longer used by
-- the stale-reset predicate) with one keyed on claimed_at.
DROP INDEX IF EXISTS idx_event_outbox_delivering;

CREATE INDEX idx_event_outbox_delivering_claimed
    ON event_outbox (claimed_at)
    WHERE status = 'DELIVERING';

-- Backfill existing DELIVERING rows so the stale-reset sweep can pick
-- them up on first pass after upgrade. Use next_attempt_at as a
-- conservative lower bound — anything currently DELIVERING at upgrade
-- time is almost certainly already stale (the broken old sweep would
-- have reset anything fresh), so dating them this way puts them past
-- the 5-minute threshold on first tick.
-- PENDING / DELIVERED / DEAD_LETTERED rows keep claimed_at = NULL —
-- correct, since they're not currently held by any dispatcher.
UPDATE event_outbox
   SET claimed_at = next_attempt_at
 WHERE status = 'DELIVERING';
