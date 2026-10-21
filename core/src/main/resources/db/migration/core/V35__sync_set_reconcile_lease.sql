-- SPDX-License-Identifier: Apache-2.0
--
-- Single-flight lease for a sync set's reconcile. Every app instance runs the
-- reconcile scheduler, and without a lease each one reconciled the same due set
-- at the same time: the runs duplicated all the source reads and target writes,
-- and all but the first to finish failed with an optimistic-lock error when they
-- saved the set. A run now claims the lease (a fresh token) before it starts,
-- heartbeats while it works, and clears it when it ends; a lease whose
-- heartbeat has gone stale belonged to a server that stopped and can be claimed.

ALTER TABLE sync_set
    ADD COLUMN reconcile_lease_token        UUID,
    ADD COLUMN reconcile_lease_heartbeat_at TIMESTAMPTZ;
