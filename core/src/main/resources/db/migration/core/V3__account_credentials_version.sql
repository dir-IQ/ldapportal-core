-- Add a credentials-version counter to accounts.
--
-- The JWT auth filter already re-checks `active` and the current role
-- on every request, so disabling or demoting an account propagates
-- immediately. But two operations that mutate the credentials
-- themselves — password reset and authType switch — left previously-
-- issued JWTs valid until expiry: a stolen session for a LOCAL admin
-- whose password the operator just reset could still browse, and the
-- "switch this admin from LOCAL to LDAP" remediation didn't kick the
-- session either.
--
-- Bumping this counter on every credential-changing op, and embedding
-- the value in the JWT as a `cv` claim, lets the filter refuse any
-- token whose `cv` doesn't match the stored value — i.e. revoke all
-- live sessions for that account at the moment its credentials
-- change.
--
-- Default 1 backfills existing rows; the column is NOT NULL because
-- the filter requires a value to compare against.

ALTER TABLE accounts
    ADD COLUMN credentials_version bigint NOT NULL DEFAULT 1;
