-- SPDX-License-Identifier: Apache-2.0
-- Bring the scheduled-report scheduler into core on the existing
-- scheduled_report_jobs table (defined in the baseline).
--
-- 1. Drop chk_report_type. The schedulable report-type set is dynamic — built-in
--    OperationalReportTypes, addon OperationalReportProvider ids, and ee
--    compliance types — so a baseline allow-list can neither enumerate it nor
--    stay in sync (the same dual-ownership drift that retired chk_feature_key in
--    V18). report_type is validated in the service against the provider registry.
--    The output_format / delivery_method CHECKs stay (those value sets are stable
--    and core-owned via the ReportOutputFormat / ReportDeliveryMethod enums).
--
-- 2. Add timezone + run_history for parity with the commercial scheduler: cron is
--    evaluated in the job's IANA zone (null = UTC) and each run appends to a
--    bounded JSON timeline. IF NOT EXISTS so this is a no-op on commercial
--    installs where ee's governance migration already added these columns —
--    avoiding the duplicate-column failure (out-of-order migrations mean the last
--    writer differs between fresh and existing commercial databases).

ALTER TABLE scheduled_report_jobs DROP CONSTRAINT IF EXISTS chk_report_type;

ALTER TABLE scheduled_report_jobs ADD COLUMN IF NOT EXISTS timezone varchar(50);

ALTER TABLE scheduled_report_jobs
    ADD COLUMN IF NOT EXISTS run_history jsonb NOT NULL DEFAULT '[]'::jsonb;
