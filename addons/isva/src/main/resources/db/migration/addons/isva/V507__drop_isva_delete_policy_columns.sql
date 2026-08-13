-- SPDX-License-Identifier: Apache-2.0
--
-- Retire the delete-behavior configuration. The secUser side now
-- mirrors the demographic entry's lifecycle automatically: deleting a
-- user deletes its secUser, disabling a user disables its secUser (via
-- the new planUserSetEnabled provisioning hook). That removes the need
-- for an operator-chosen delete policy, so:
--
--   * delete_policy          (DISABLE | HARD_DELETE) — delete is now
--                            always a hard delete of both entries; the
--                            soft path is the separate disable verb.
--   * on_demographic_delete  (LEAVE | DISABLE_AND_MARK) — only had
--                            meaning during a soft-delete, which no
--                            longer exists.
--
-- both drop out of the schema. No data migration is needed: the columns
-- carried policy, not user data.

ALTER TABLE vendor_integration_isva_config
    DROP COLUMN IF EXISTS delete_policy,
    DROP COLUMN IF EXISTS on_demographic_delete;
