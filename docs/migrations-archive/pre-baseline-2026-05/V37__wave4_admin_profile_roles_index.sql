-- Wave 4.4: dedicated index on admin_account_id for findAllByAdminAccountId queries.
-- The existing UNIQUE(admin_account_id, profile_id) helps paired lookups but not
-- queries that filter on admin_account_id alone.
CREATE INDEX IF NOT EXISTS idx_apr_admin_account
    ON admin_profile_roles (admin_account_id);
