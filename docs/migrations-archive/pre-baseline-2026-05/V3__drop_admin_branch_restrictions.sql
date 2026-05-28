-- Remove the admin_branch_restrictions table (branch restriction feature removed)
DROP INDEX IF EXISTS idx_abr_admin_realm;
DROP TABLE IF EXISTS admin_branch_restrictions;
