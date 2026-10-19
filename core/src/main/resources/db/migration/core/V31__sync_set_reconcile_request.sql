-- SPDX-License-Identifier: Apache-2.0
--
-- A durable "please reconcile" signal for a sync set. The changelog poller
-- raises it (first poll of a cookie link, a changelog gap, a refused cookie)
-- instead of reconciling inline. Counters rather than timestamps, so no
-- comparison depends on clocks agreeing across app instances:
--   reconcile_request_seq   — incremented by each request;
--   reconcile_attempted_seq — the request_seq the latest reconcile saw when it
--                             started, recorded when it finishes;
--   reconcile_handled_seq   — the same, but only for a reconcile whose source
--                             enumeration completed.
-- A request newer than the last attempt makes the set due at once; one that an
-- incomplete reconcile attempted but didn't handle is retried, spaced out. A
-- reconcile that started before a request records the older value and so
-- leaves that request pending. All three are written only by targeted UPDATEs,
-- never by an entity save, so a reconcile that loaded the set earlier can't
-- overwrite a newer request.

ALTER TABLE sync_set ADD COLUMN reconcile_request_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_set ADD COLUMN reconcile_attempted_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_set ADD COLUMN reconcile_handled_seq BIGINT NOT NULL DEFAULT 0;
