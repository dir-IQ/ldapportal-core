-- SPDX-License-Identifier: Apache-2.0
--
-- Make the sync_links → directory_connections foreign keys ON DELETE CASCADE,
-- to match every other reference to directory_connections in the schema
-- (provisioning_profiles, entra_*, user/group base DNs, lifecycle playbooks,
-- registration_requests, …). Without this, deleting a directory that is the
-- source or target of a sync link fails with a raw foreign-key violation
-- instead of tearing down its sync configuration like all other directory-
-- scoped data. With CASCADE, removing a directory drops its sync_links, which
-- in turn cascade to sync_set → membership / recompute_request (those FKs are
-- already ON DELETE CASCADE).

ALTER TABLE public.sync_links DROP CONSTRAINT sync_links_source_dir_id_fkey;
ALTER TABLE public.sync_links
    ADD CONSTRAINT sync_links_source_dir_id_fkey
    FOREIGN KEY (source_dir_id) REFERENCES public.directory_connections(id) ON DELETE CASCADE;

ALTER TABLE public.sync_links DROP CONSTRAINT sync_links_target_dir_id_fkey;
ALTER TABLE public.sync_links
    ADD CONSTRAINT sync_links_target_dir_id_fkey
    FOREIGN KEY (target_dir_id) REFERENCES public.directory_connections(id) ON DELETE CASCADE;
