-- Rename the profile's single target-OU column to make room for a
-- distinct group container, so groups can be administered from a
-- subtree separate from users.
--
-- target_ou_dn (where users are created) -> target_user_dn.
-- target_group_dn is new: backfilled to the user DN for every existing
-- profile, so behaviour is unchanged until an admin sets a distinct
-- value. NOT NULL after backfill — the service defaults a blank request
-- value to the user DN, matching this backfill.

ALTER TABLE provisioning_profiles
    RENAME COLUMN target_ou_dn TO target_user_dn;

ALTER TABLE provisioning_profiles
    ADD COLUMN target_group_dn character varying(500);

UPDATE provisioning_profiles
    SET target_group_dn = target_user_dn
    WHERE target_group_dn IS NULL;

ALTER TABLE provisioning_profiles
    ALTER COLUMN target_group_dn SET NOT NULL;
