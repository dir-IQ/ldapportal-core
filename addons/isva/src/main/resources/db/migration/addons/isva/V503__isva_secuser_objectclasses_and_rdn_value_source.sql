-- SPDX-License-Identifier: Apache-2.0
--
-- Generalize the linked-mode secUser naming + identity so deployments whose
-- IVIA registry doesn't follow the stock secUUID/secLogin convention can be
-- configured rather than code-changed. Two facets, previously hard-coded:
--
--   * secuser_object_classes   — the objectClass set written to the secUser
--     identity (default 'secUser'; e.g. a deployment that names entries on
--     principalName needs the 'eUser' auxiliary class that defines it). Applies
--     to BOTH topology modes — inline overlays these onto the demographic entry,
--     linked stamps them on the standalone secUser entry.
--   * secuser_rdn_value_source — decouples the RDN *value* from the attribute
--     *name*. Previously the name implied the value (secUUID -> generated UUID,
--     secLogin -> uid); now any attribute name pairs with an explicit source.
--
-- secuser_rdn_attribute stays as-is (now free-form, no longer restricted to the
-- two known names by application code).
--
-- Forward-only, additive. Postgres backfills existing rows with the column
-- DEFAULTs; the UPDATE preserves the legacy secLogin = "mirror uid" behaviour.

ALTER TABLE vendor_integration_isva_config
    ADD COLUMN secuser_object_classes   TEXT        DEFAULT 'secUser',
    ADD COLUMN secuser_rdn_value_source VARCHAR(16) DEFAULT 'GENERATED_UUID';

-- Preserve behaviour for rows created under the old name-implies-value rule:
-- secLogin meant "mirror the user's uid", everything else meant "generate a UUID".
UPDATE vendor_integration_isva_config
   SET secuser_rdn_value_source = 'UID'
 WHERE secuser_rdn_attribute = 'secLogin';

ALTER TABLE vendor_integration_isva_config
    ADD CONSTRAINT chk_isva_rdn_value_source
        CHECK (secuser_rdn_value_source IS NULL
               OR secuser_rdn_value_source IN ('GENERATED_UUID', 'UID'));
