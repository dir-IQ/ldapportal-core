-- Encrypted OIDC refresh token issued by the IdP when the client requests
-- the `offline_access` scope (or the IdP issues one by default). Used on
-- logout to call the IdP's revocation_endpoint — revoking the refresh
-- token forces the user to complete a fresh authorization code flow next
-- time, so a disabled IdP account can't log back in by replaying.
ALTER TABLE accounts
    ADD COLUMN oidc_refresh_token_enc TEXT;
