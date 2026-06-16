-- SPDX-License-Identifier: Apache-2.0
-- Per-sync-set override of the attributes excluded from projection/diff.
-- NULL = use the engine defaults (operational attributes + password values);
-- a JSON array (including []) is used verbatim ([] = exclude nothing).
ALTER TABLE sync_set ADD COLUMN excluded_attributes jsonb;
