-- SPDX-License-Identifier: Apache-2.0
--
-- Per-provisioning-profile ISVA override. Layered on the per-directory
-- vendor_integration_isva_config (V500): the directory `enabled` flag
-- is the authoritative on/kill-switch, and a profile row can only
-- NARROW it — i.e. exempt that profile from ISVA. There is no
-- FORCE_ON; disabling ISVA on a directory is guaranteed off
-- everywhere.
--
-- A profile with no row is treated as INHERIT (follow the directory).
-- The override is addon-owned and keyed by profile_id so core stays
-- ISVA-agnostic (see docs/edition-boundary.md).
--
-- V501 continues the addon's V500-block (core V1-V99, ee V100-V499,
-- isva addon V500+).

CREATE TABLE isva_profile_override (
    profile_id   UUID         NOT NULL PRIMARY KEY,
    override     VARCHAR(16)  NOT NULL DEFAULT 'INHERIT',
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by   VARCHAR(255),

    CONSTRAINT fk_isva_profile_override_profile
        FOREIGN KEY (profile_id)
        REFERENCES provisioning_profiles (id)
        ON DELETE CASCADE,

    CONSTRAINT chk_isva_profile_override
        CHECK (override IN ('INHERIT', 'FORCE_OFF'))
);

COMMENT ON TABLE isva_profile_override IS
    'Per-profile ISVA narrowing override (INHERIT | FORCE_OFF) — see docs/superpowers/specs/2026-05-24-isva-profile-scoped-provisioning-design.md';
