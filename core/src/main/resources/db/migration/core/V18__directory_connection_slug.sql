-- SPDX-License-Identifier: Apache-2.0
--
-- Stable, immutable, URL-safe external key for directory connections, to
-- support Infrastructure-as-Code automation (see
-- docs/plans/2026-06-05-iac-automation-design.md §4.1). Automation tools
-- need a client-owned identifier they can upsert against across runs;
-- the surrogate UUID id is server-generated and the display_name is
-- mutable, so neither works as the declarative key. The slug fills that
-- gap and backs PUT /api/v1/superadmin/directories/by-slug/{slug}.
--
-- Forward-only. Existing rows are backfilled with a slug derived from
-- display_name; collisions (including blank/symbol-only names) are made
-- unique with a deterministic numeric suffix so the UNIQUE index below
-- can be created without manual intervention.

ALTER TABLE directory_connections
    ADD COLUMN slug VARCHAR(100);

WITH base AS (
    SELECT id,
           NULLIF(
               trim(BOTH '-' FROM regexp_replace(lower(trim(display_name)),
                                                 '[^a-z0-9]+', '-', 'g')),
               '') AS candidate
    FROM directory_connections
),
normalized AS (
    SELECT id, COALESCE(candidate, 'directory') AS candidate FROM base
),
deduped AS (
    SELECT id,
           candidate,
           row_number() OVER (PARTITION BY candidate ORDER BY id) AS rn
    FROM normalized
)
UPDATE directory_connections d
SET slug = CASE WHEN dd.rn = 1 THEN dd.candidate
                ELSE dd.candidate || '-' || dd.rn END
FROM deduped dd
WHERE d.id = dd.id;

ALTER TABLE directory_connections
    ALTER COLUMN slug SET NOT NULL;

CREATE UNIQUE INDEX uq_directory_connections_slug
    ON directory_connections (slug);
