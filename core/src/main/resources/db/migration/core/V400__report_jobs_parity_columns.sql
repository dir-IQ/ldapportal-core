-- SPDX-License-Identifier: Apache-2.0
-- Add the scheduled-report parity columns (timezone + run_history) to
-- scheduled_report_jobs. These give the core scheduler parity with the
-- commercial one: cron is evaluated in the job's IANA zone (null = UTC) and each
-- run appends to a bounded JSON timeline.
--
-- WHY THIS LIVES IN CORE BUT AT A HIGH (POST-ADDON) VERSION
-- --------------------------------------------------------
-- These columns must exist in BOTH editions: core's ScheduledReportJob entity
-- maps them, so Hibernate `validate` requires them on the community (core-only)
-- build too. They therefore have to be added from db/migration/core (the only
-- tree a community build ships).
--
-- But on the commercial build, ee's governance migration V108 ALSO adds these
-- columns — with a plain, non-idempotent `ADD COLUMN`. Core and ee share one
-- Flyway timeline ordered by version, and ee uses the V100+ band, so a normal
-- low-numbered core migration would run BEFORE ee V108 and make V108 fail on a
-- fresh commercial install ("column already exists").
--
-- Versioning this migration above the ee bands (governance ~V1xx, hr ~V2xx,
-- alerting ~V3xx) makes it run LAST on commercial: ee V108 adds the columns
-- first, and this migration's IF NOT EXISTS is then a no-op. On a community
-- build (no ee migrations on the classpath) it simply adds them. Either way the
-- entity validates and there is no duplicate-column collision.

ALTER TABLE scheduled_report_jobs ADD COLUMN IF NOT EXISTS timezone varchar(50);

ALTER TABLE scheduled_report_jobs
    ADD COLUMN IF NOT EXISTS run_history jsonb NOT NULL DEFAULT '[]'::jsonb;
