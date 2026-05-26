-- V13: OIDC authentication support
--
-- 1. Drop the unused admin_auth_type column and its CHECK constraint
-- 2. Create the enabled_auth_types join table (replaces single-value column)
-- 3. Add OIDC provider columns to application_settings
-- 4. Update the accounts CHECK constraint to allow OIDC

-- ── 1. Drop dead admin_auth_type column ──────────────────────────────────────

ALTER TABLE application_settings
    DROP CONSTRAINT IF EXISTS chk_admin_auth_type;

ALTER TABLE application_settings
    DROP COLUMN IF EXISTS admin_auth_type;

-- ── 2. Create enabled_auth_types collection table ────────────────────────────

CREATE TABLE enabled_auth_types (
    settings_id  UUID         NOT NULL REFERENCES application_settings(id) ON DELETE CASCADE,
    auth_type    VARCHAR(10)  NOT NULL,
    CONSTRAINT chk_enabled_auth_type CHECK (auth_type IN ('LOCAL', 'LDAP', 'OIDC')),
    PRIMARY KEY (settings_id, auth_type)
);

-- Seed with LOCAL for any existing settings row
INSERT INTO enabled_auth_types (settings_id, auth_type)
SELECT id, 'LOCAL' FROM application_settings;

-- ── 3. Add OIDC provider columns ─────────────────────────────────────────────

ALTER TABLE application_settings
    ADD COLUMN oidc_issuer_url        VARCHAR(1000),
    ADD COLUMN oidc_client_id         VARCHAR(500),
    ADD COLUMN oidc_client_secret_enc TEXT,
    ADD COLUMN oidc_scopes            VARCHAR(500)  DEFAULT 'openid profile email',
    ADD COLUMN oidc_username_claim    VARCHAR(100)  DEFAULT 'preferred_username';

-- ── 4. Allow OIDC in accounts.auth_type ──────────────────────────────────────

ALTER TABLE accounts
    DROP CONSTRAINT IF EXISTS chk_auth_type;

ALTER TABLE accounts
    ADD CONSTRAINT chk_auth_type CHECK (auth_type IN ('LOCAL', 'LDAP', 'OIDC'));
