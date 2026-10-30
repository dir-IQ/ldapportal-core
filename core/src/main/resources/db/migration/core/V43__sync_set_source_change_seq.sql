-- SPDX-License-Identifier: Apache-2.0
--
-- A per-set counter of source changes enqueued for recompute. The changelog
-- poller and the app-intercept capture increment it before each enqueue. A
-- reconcile reads it before its source scan and, while it hasn't moved, hands
-- the engine the entry the scan already read instead of reading the source
-- again; once it moves, a change raced the run and entries are read afresh.
-- Written only by a targeted UPDATE, never by an entity save.

ALTER TABLE sync_set ADD COLUMN source_change_seq BIGINT NOT NULL DEFAULT 0;
