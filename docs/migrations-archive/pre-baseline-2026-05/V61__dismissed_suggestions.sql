-- Per-user dismissed configuration suggestions.
CREATE TABLE dismissed_suggestions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    suggestion_key  VARCHAR(200) NOT NULL,
    dismissed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account_id, suggestion_key)
);
