-- Scope admin_feature_permissions to an optional profile.
-- NULL profile_id preserves today's admin-wide semantics (overrides apply to
-- every profile the admin has access to). A non-NULL value tightens the
-- override to that specific profile only.
--
-- The existing uniqueness constraint (admin_account_id, feature_key) can't
-- simply be extended to include profile_id because Postgres treats NULLs as
-- distinct in multi-column unique constraints — which would silently allow
-- duplicate admin-wide rows for the same (admin, feature). Split into two
-- partial unique indexes instead:
--   * admin-wide:  one row per (admin, feature) when profile_id IS NULL
--   * per-profile: one row per (admin, profile, feature) when profile_id IS NOT NULL

ALTER TABLE admin_feature_permissions
    ADD COLUMN profile_id UUID REFERENCES provisioning_profiles(id) ON DELETE CASCADE;

ALTER TABLE admin_feature_permissions
    DROP CONSTRAINT IF EXISTS uq_admin_feature;

CREATE UNIQUE INDEX uq_admin_feature_global
    ON admin_feature_permissions (admin_account_id, feature_key)
    WHERE profile_id IS NULL;

CREATE UNIQUE INDEX uq_admin_feature_per_profile
    ON admin_feature_permissions (admin_account_id, profile_id, feature_key)
    WHERE profile_id IS NOT NULL;

CREATE INDEX idx_afp_admin_profile
    ON admin_feature_permissions (admin_account_id, profile_id);
