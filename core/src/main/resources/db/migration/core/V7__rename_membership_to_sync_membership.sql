-- SPDX-License-Identifier: Apache-2.0
--
-- Rename the membership index table to sync_membership for a consistent sync_*
-- namespace (sync_links, sync_set, sync_membership). Postgres preserves the
-- data, primary key, foreign key, indexes and check constraint across a table
-- rename; the constraint/index names are renamed too so they reflect the new
-- table. The JPA entity stays `Membership` (only its @Table mapping changes),
-- so JPQL/derived queries are unaffected.

ALTER TABLE membership RENAME TO sync_membership;

ALTER TABLE sync_membership RENAME CONSTRAINT membership_pkey TO sync_membership_pkey;
ALTER TABLE sync_membership RENAME CONSTRAINT membership_state_check TO sync_membership_state_check;
ALTER TABLE sync_membership RENAME CONSTRAINT membership_sync_set_id_fkey TO sync_membership_sync_set_id_fkey;

ALTER INDEX idx_membership_epoch RENAME TO idx_sync_membership_epoch;
ALTER INDEX idx_membership_srcdn RENAME TO idx_sync_membership_srcdn;
ALTER INDEX idx_membership_state RENAME TO idx_sync_membership_state;
