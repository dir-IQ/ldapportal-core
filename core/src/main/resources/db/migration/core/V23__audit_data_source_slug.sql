-- SPDX-License-Identifier: Apache-2.0
-- Give audit data sources a stable, client-owned IaC key (slug), mirroring
-- directory_connections. The config export/reconcile surface addresses an audit
-- source by a name that survives a fresh install — a server-assigned UUID does
-- not. Backfill existing rows from display_name, de-duplicating any collisions,
-- then enforce NOT NULL + uniqueness.

ALTER TABLE audit_data_sources ADD COLUMN slug varchar(100);

UPDATE audit_data_sources a
SET slug = d.slug
FROM (
    SELECT id,
           -- First row for a given base keeps it; collisions get a -N suffix.
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
                                       regexp_replace(lower(display_name), '[^a-z0-9]+', '-', 'g'),
                                       '(^-+)|(-+$)', '', 'g'),
                                   90),
                               '-+$', '', 'g'),
                           ''),
                       'audit-source') AS base
            FROM audit_data_sources
        ) norm
    ) ranked
) d
WHERE a.id = d.id;

ALTER TABLE audit_data_sources ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX uq_audit_data_sources_slug ON audit_data_sources USING btree (slug);
