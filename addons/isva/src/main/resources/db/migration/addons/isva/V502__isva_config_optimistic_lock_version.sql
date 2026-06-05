-- SPDX-License-Identifier: Apache-2.0
--
-- JPA @Version optimistic-lock column for the ISVA per-directory config, to
-- match the core IaC resources (see
-- docs/plans/2026-06-05-iac-automation-design.md §4.4). With a version counter
-- two concurrent writes to the same directory's ISVA config can't silently
-- clobber each other, and the counter is surfaced as an ETag so a client can
-- send If-Match for a pre-write check.
--
-- Forward-only, additive. Existing rows start at version 0 (the default
-- Hibernate assigns to a freshly persisted entity), so the upgrade is a no-op
-- until the next write to each row.

ALTER TABLE vendor_integration_isva_config
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
