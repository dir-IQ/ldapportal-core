-- V69: API tokens for machine authentication (Terraform, webhooks, CI).
-- See docs/superpowers/specs/2026-04-22-api-token-auth-design.md for the
-- full rationale and non-goals.

CREATE TABLE api_tokens (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(100) NOT NULL,
    description       VARCHAR(500),

    -- SHA-256 of the plaintext token (32 bytes). Deterministic so we can
    -- index; UNIQUE so auth is a single-row equality lookup.
    token_hash        BYTEA        NOT NULL,

    -- First 16 chars of the plaintext ("ldap_pat_" + 7 random chars). Column is
    -- VARCHAR(32) for forward-compat headroom; v1 stores exactly 16 chars.
    -- Shown in list views so operators can correlate a leaked prefix back
    -- to a row without needing the full secret.
    token_prefix      VARCHAR(32)  NOT NULL,

    -- Forward-compat for scope-restricted tokens. v1 always NULL.
    scopes            JSONB,

    -- Creator. FK to accounts; cascade delete because leaving orphaned
    -- tokens after admin deletion is a security liability.
    created_by_id     UUID         NOT NULL
                                   REFERENCES accounts(id) ON DELETE CASCADE,

    -- Lifecycle timestamps.
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    last_used_at      TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,

    CONSTRAINT chk_api_tokens_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_api_tokens_expiry_future  CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX uq_api_tokens_hash       ON api_tokens (token_hash);
CREATE INDEX        idx_api_tokens_created_by ON api_tokens (created_by_id);
-- Partial: active tokens only; cleanup/expiry sweeps never need the revoked tail.
CREATE INDEX        idx_api_tokens_expires_at_active ON api_tokens (expires_at)
    WHERE revoked_at IS NULL;
