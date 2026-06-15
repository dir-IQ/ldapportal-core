-- SPDX-License-Identifier: Apache-2.0
--
-- Per-directory ISVA full-mode integration configuration. Keyed by
-- directory_connection_id; absent row means the addon is inert for
-- that directory. New deployments don't get any rows until an
-- operator explicitly enables ISVA on a directory via the UI (P4).
--
-- The CHECK constraint at the bottom enforces that
-- management_dit_base_dn is set when topology_mode = LINKED — the
-- linked-mode interceptor needs that DN to construct the secUser
-- entry's parent. Inline mode leaves it NULL.
--
-- V500 picked to leave room above the existing core / ee migration
-- ranges (core V1-V99, ee V100-V499). Future addons get their own
-- V-block.

CREATE TABLE vendor_integration_isva_config (
    directory_connection_id     UUID         NOT NULL PRIMARY KEY,
    enabled                     BOOLEAN      NOT NULL DEFAULT FALSE,
    topology_mode               VARCHAR(16)  NOT NULL DEFAULT 'INLINE',
    sec_authority               VARCHAR(255) DEFAULT 'Default',
    default_valid_until_years   INTEGER      NOT NULL DEFAULT 100,
    delete_policy               VARCHAR(16)  NOT NULL DEFAULT 'DISABLE',
    require_sec_group           BOOLEAN      NOT NULL DEFAULT TRUE,

    -- LINKED-mode-only — NULL when topology_mode = 'INLINE'
    management_dit_base_dn      TEXT,
    secuser_rdn_attribute       VARCHAR(64)  DEFAULT 'secUUID',
    group_member_target         VARCHAR(16)  DEFAULT 'DEMOGRAPHIC_DN',
    on_demographic_delete       VARCHAR(24)  DEFAULT 'LEAVE',

    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(255),

    CONSTRAINT fk_isva_config_directory
        FOREIGN KEY (directory_connection_id)
        REFERENCES directory_connections (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_isva_topology_mode
        CHECK (topology_mode IN ('INLINE', 'LINKED')),

    CONSTRAINT chk_isva_delete_policy
        CHECK (delete_policy IN ('DISABLE', 'HARD_DELETE')),

    CONSTRAINT chk_isva_group_member_target
        CHECK (group_member_target IS NULL
               OR group_member_target IN ('DEMOGRAPHIC_DN', 'SECUSER_DN')),

    CONSTRAINT chk_isva_on_demographic_delete
        CHECK (on_demographic_delete IS NULL
               OR on_demographic_delete IN ('LEAVE', 'DISABLE_AND_MARK')),

    -- The defence-in-depth constraint. The UI validates this before
    -- save too, but the DB-level check guarantees no LINKED-mode row
    -- can exist without the required base DN regardless of how the
    -- write got there.
    CONSTRAINT chk_isva_linked_requires_management_dit
        CHECK (topology_mode = 'INLINE' OR management_dit_base_dn IS NOT NULL)
);

COMMENT ON TABLE vendor_integration_isva_config IS
    'Per-directory ISVA full-mode integration config — see docs/superpowers/specs/2026-05-20-isva-full-mode-integration-design.md';
