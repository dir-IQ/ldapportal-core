-- SPDX-License-Identifier: Apache-2.0
-- Drop the admin_feature_permissions.feature_key CHECK constraint.
--
-- chk_feature_key duplicated, in the schema, an allow-list that the FeatureKey
-- enum already owns. The two drift apart every time a key is added: the baseline
-- list fell behind the enum once already and had to be realigned in V14. Because
-- feature_key is persisted only through the typed FeatureKey enum via
-- FeatureKeyConverter (which rejects unknown values with IllegalArgumentException
-- -> 400 on read, and is bound from the enum on write), the enum is the single
-- source of truth for validity. The DB-level mirror adds no protection a valid
-- enum value can't already satisfy — only a recurring drift hazard — so it is
-- removed. Validation now lives in code (the enum + converter) exclusively.

ALTER TABLE admin_feature_permissions DROP CONSTRAINT IF EXISTS chk_feature_key;
