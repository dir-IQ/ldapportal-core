-- SPDX-License-Identifier: Apache-2.0
--
-- The unified per-attribute secUser overlay model. Each secUser attribute
-- becomes one row carrying its own value — a literal, or a computed
-- expression (user-attribute and sec-attribute references plus the uuid(),
-- now() and nowPlusYears(n) functions) — replacing the split representation
-- of a secuser_overlay_attributes name-list alongside standalone
-- secAuthority / sec_login_type / default_valid_until_years value fields.
-- (Expression syntax is deliberately not shown here: Flyway would read a
-- dollar-brace token in this comment as a placeholder and fail to parse.)
--
-- Stored as a JSON array in a TEXT column (see SecUserAttributesConverter).
--
-- Nullable and left NULL for existing rows: a NULL column means "not migrated
-- yet", and IsvaSecUserPlans.effectiveAttributes derives an equivalent model
-- from the legacy value fields on the fly, so provisioning is byte-identical
-- until an explicit model is saved. The next config save persists the derived
-- model, making it explicit. Seeding the JSON here in SQL would mean
-- reconstructing a conditional array from a CSV column — fragile and
-- redundant with the deriver — so it's done lazily in application code
-- instead.

ALTER TABLE vendor_integration_isva_config
    ADD COLUMN IF NOT EXISTS secuser_attributes TEXT;
