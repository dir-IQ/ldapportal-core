-- SPDX-License-Identifier: Apache-2.0
--
-- profile_attribute_configs.column_span: default 6 → 3.
--
-- 6 (full-width) was the original default. In practice it makes every
-- new attribute config render as a one-per-row stack — the user-form
-- modal feels much taller than it needs to. Most LDAP attributes fit
-- comfortably in a half-row (cn, mail, sn, displayName, title,
-- department, ...). Setting the default to 3 produces a two-column
-- layout out of the box; admins still set 6 explicitly for genuinely
-- long fields (description, postalAddress), and 2 for short ones
-- (employeeNumber, country code).
--
-- Existing rows are left alone. An admin who explicitly chose 6 for a
-- given attribute presumably meant it; silently rewriting their saved
-- config would surprise. Re-saving a profile through the editor uses
-- the new default for any newly-added attribute config; existing
-- attribute rows keep their value.

ALTER TABLE profile_attribute_configs
    ALTER COLUMN column_span SET DEFAULT 3;
