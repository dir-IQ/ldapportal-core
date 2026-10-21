-- SPDX-License-Identifier: Apache-2.0
--
-- More detail on each recorded reconcile run, so a past run can be explained
-- without the server logs:
--   instance_name    the app instance (host) that ran it
--   planned_at       when the source scan and plan finished: scan time is
--                    planned_at - started_at, apply time finished_at - planned_at
--   settings         the set's settings and delete-guard limits at run time (JSON)
--   failure_reasons  failed / faulted entries grouped by reason, with counts and
--                    an example (JSON)
--   samples          up to a few target DNs per outcome (JSON; DNs only, no
--                    attribute values)
--   verified_at, verify_*  the content verification that ran right after the
--                    reconcile (drift left behind), when its scan was complete

ALTER TABLE sync_reconcile_run
    ADD COLUMN instance_name    TEXT,
    ADD COLUMN planned_at       TIMESTAMPTZ,
    ADD COLUMN settings         TEXT,
    ADD COLUMN failure_reasons  TEXT,
    ADD COLUMN samples          TEXT,
    ADD COLUMN verified_at      TIMESTAMPTZ,
    ADD COLUMN verify_missing   INTEGER,
    ADD COLUMN verify_orphan    INTEGER,
    ADD COLUMN verify_mismatch  INTEGER;
