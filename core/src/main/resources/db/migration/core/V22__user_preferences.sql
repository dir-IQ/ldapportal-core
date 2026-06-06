-- SPDX-License-Identifier: Apache-2.0
-- Single home for all per-account UI customizations (themes, density, table
-- column state, saved filters, search history, modal sizes, ...). Previously
-- scattered across browser localStorage; now persisted server-side as one
-- namespaced JSONB document per account so preferences follow the user across
-- browsers and devices.

CREATE TABLE user_preferences (
    account_id  UUID        PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    document    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version     BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill appearance from the now-deprecated account columns so existing
-- users keep their theme/density on first login after the upgrade. Only the
-- non-default values are worth carrying, but seeding every account is simpler
-- and harmless — the frontend tolerates explicit defaults.
INSERT INTO user_preferences (account_id, document)
SELECT a.id,
       jsonb_build_object(
           'schemaVersion', 1,
           'appearance', jsonb_build_object(
               'theme',   COALESCE(a.theme_preference,   'light'),
               'density', COALESCE(a.density_preference, 'comfortable')
           )
       )
FROM accounts a;

-- Appearance now lives in user_preferences.document.appearance — the single
-- source of truth. Drop the columns so there is exactly one place.
ALTER TABLE accounts DROP COLUMN theme_preference;
ALTER TABLE accounts DROP COLUMN density_preference;
