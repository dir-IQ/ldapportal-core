-- SPDX-License-Identifier: Apache-2.0
--
-- Default the theme preference for newly-created accounts to 'light' (was
-- 'system'). Keeps the DB column default in sync with the entity default
-- (Account.themePreference). Only affects rows inserted without an explicit
-- value; existing accounts keep whatever preference they already have.

ALTER TABLE accounts
    ALTER COLUMN theme_preference SET DEFAULT 'light';
