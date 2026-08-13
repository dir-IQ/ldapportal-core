-- SPDX-License-Identifier: Apache-2.0
--
-- Make the optional sec* overlay attributes written onto the secUser identity
-- configurable per directory. IBM's secUser schema varies between deployments:
-- some registries' secUser objectClass does not permit secValidUntil or
-- secLogin, so unconditionally writing them fails every grant with "attribute
-- <x> is not allowed by objectClass secUser". Operators now trim the set to
-- match their schema (validated by the config Probe).
--
-- secLoginType / secAuthority are NOT part of this list — they are MUST on
-- IBM's stock secUser and always written; only their values are configurable.
--
-- Forward-only, additive. Existing rows backfill to the column DEFAULT, which
-- is the full set the code wrote before this change — preserving behaviour.

ALTER TABLE vendor_integration_isva_config
    ADD COLUMN secuser_overlay_attributes TEXT
        DEFAULT 'secLogin,secAcctValid,secPwdValid,secValidUntil,secPwdLastChanged';
