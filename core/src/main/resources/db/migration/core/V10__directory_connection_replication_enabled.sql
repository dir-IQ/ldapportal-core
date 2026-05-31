-- SPDX-License-Identifier: Apache-2.0
-- R2: per-directory replication master switch.
--
-- Forward-only. Defaults false so replication stays opt-in per directory
-- and the upgrade is a no-op for existing rows (no behaviour change until
-- an operator flips the toggle). The per-directory gate is distinct from
-- the per-link replication_links.enabled flag: directory-off means no
-- capture at all (no event rows accumulate); link-off pauses one link's
-- dispatch while capture continues.
ALTER TABLE directory_connections
    ADD COLUMN replication_enabled BOOLEAN NOT NULL DEFAULT false;
