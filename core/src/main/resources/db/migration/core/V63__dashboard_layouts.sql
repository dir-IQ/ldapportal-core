-- Per-account dashboard layout customizations (panel/metric order + visibility).
-- One row per account; delete the row to reset to defaults.
CREATE TABLE dashboard_layouts (
    account_id  UUID        PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    layout      JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
