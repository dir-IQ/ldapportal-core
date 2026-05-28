-- OIDC security hardening: persist pending flows so they survive restarts
-- and horizontal scaling, add a configured redirect URI to remove reliance
-- on the spoofable Host header, and keep the last OIDC ID token per account
-- so logout can redirect to the IdP's end_session_endpoint with id_token_hint.

-- Pending OAuth2/OIDC flow state, keyed by the random state parameter.
-- Consumed exactly once on callback and swept periodically by write-time
-- cleanup in OidcAuthenticationService.
CREATE TABLE oidc_pending_flows (
    state         TEXT        PRIMARY KEY,
    nonce         TEXT        NOT NULL,
    code_verifier TEXT        NOT NULL,
    redirect_uri  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_oidc_pending_flows_created_at ON oidc_pending_flows(created_at);

-- Configured redirect URI for the OIDC callback. When set, the server uses
-- this instead of deriving the URI from the HTTP request (which can be
-- spoofed via the Host header).
ALTER TABLE application_settings
    ADD COLUMN oidc_redirect_uri TEXT;

-- Last OIDC ID token for the account, used as id_token_hint on RP-initiated
-- logout so the IdP can terminate exactly the right session without prompting
-- the user. Overwritten on each OIDC login; cleared on logout.
ALTER TABLE accounts
    ADD COLUMN oidc_id_token TEXT;
