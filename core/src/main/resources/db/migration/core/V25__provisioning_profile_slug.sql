-- SPDX-License-Identifier: Apache-2.0
-- Give provisioning profiles a stable, client-owned IaC key (slug), mirroring
-- directory_connections and audit_data_sources. Lets config export/reconcile —
-- and admin profile-role / ISVA-override references — address a profile by a
-- name that survives a fresh install rather than a server-assigned UUID.
--
-- The slug is GLOBALLY unique (not per-directory, unlike the existing
-- (directory_id, name) constraint): a single stable key is what a profile-role
-- reference needs. Backfill from name, de-duplicating collisions across all
-- directories, then enforce NOT NULL + uniqueness.

ALTER TABLE provisioning_profiles ADD COLUMN slug varchar(100);

UPDATE provisioning_profiles a
SET slug = d.slug
FROM (
    SELECT id,
           CASE WHEN cnt = 1 THEN base ELSE base || '-' || rn END AS slug
    FROM (
        SELECT id, base,
               row_number() OVER (PARTITION BY base ORDER BY created_at, id) AS rn,
               count(*)     OVER (PARTITION BY base)                          AS cnt
        FROM (
            SELECT id, created_at,
                   COALESCE(
                       NULLIF(
                           regexp_replace(
                               left(
                                   regexp_replace(
                                       regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'),
                                       '(^-+)|(-+$)', '', 'g'),
                                   90),
                               '-+$', '', 'g'),
                           ''),
                       'profile') AS base
            FROM provisioning_profiles
        ) norm
    ) ranked
) d
WHERE a.id = d.id;

ALTER TABLE provisioning_profiles ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX uq_provisioning_profiles_slug ON provisioning_profiles USING btree (slug);
