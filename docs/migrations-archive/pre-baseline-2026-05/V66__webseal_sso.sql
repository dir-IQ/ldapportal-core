-- WebSEAL (IBM Verify Identity Access) header-based SSO support.
-- Pattern: WebSEAL terminates auth at the edge and forwards iv-user /
-- iv-groups HTTP headers to the backend. This app trusts those headers
-- ONLY when the request peer is in a configured CIDR allow-list.
--
-- Account model is pre-provisioning: admins must already exist with
-- authType=WEBSEAL and username matching the iv-user value — iv-groups
-- is logged for audit but never used for role assignment.

-- 1. Widen accounts.auth_type CHECK to allow 'WEBSEAL'.
ALTER TABLE accounts
    DROP CONSTRAINT IF EXISTS chk_auth_type;

ALTER TABLE accounts
    ADD CONSTRAINT chk_auth_type CHECK (auth_type IN ('LOCAL', 'LDAP', 'OIDC', 'WEBSEAL'));

-- 2. Widen enabled_auth_types CHECK likewise.
ALTER TABLE enabled_auth_types
    DROP CONSTRAINT IF EXISTS chk_enabled_auth_type;

ALTER TABLE enabled_auth_types
    ADD CONSTRAINT chk_enabled_auth_type CHECK (auth_type IN ('LOCAL', 'LDAP', 'OIDC', 'WEBSEAL'));

-- 3. WebSEAL configuration columns on application_settings.
--    Empty trusted_proxies effectively disables the feature even when
--    WEBSEAL is in enabledAuthTypes — safer-by-default.
ALTER TABLE application_settings
    ADD COLUMN webseal_trusted_proxies TEXT,
    ADD COLUMN webseal_user_header     VARCHAR(100) DEFAULT 'iv-user',
    ADD COLUMN webseal_groups_header   VARCHAR(100) DEFAULT 'iv-groups',
    ADD COLUMN webseal_logout_url      VARCHAR(500) DEFAULT '/pkmslogout';
