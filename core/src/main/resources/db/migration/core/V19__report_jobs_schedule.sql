-- SPDX-License-Identifier: Apache-2.0
-- Bring the scheduled-report scheduler fully into core on the core-owned
-- scheduled_report_jobs table (created in the baseline). Core is the single
-- owner of this table across editions: ee no longer mutates it (its old V108
-- column/constraint changes were removed), so these run cleanly on both the
-- community (core-only) and commercial (core+ee) builds with no cross-edition
-- collision on the shared Flyway timeline.
--
-- 1. Drop chk_report_type. The schedulable report-type set is dynamic — built-in
--    OperationalReportTypes, addon OperationalReportProvider ids, and ee
--    compliance types — so a baseline allow-list can neither enumerate it nor
--    stay in sync (the same dual-ownership drift that retired chk_feature_key in
--    V18). report_type is validated in the service against the provider registry.
--    The output_format / delivery_method CHECKs stay (those value sets are stable
--    and core-owned via the ReportOutputFormat / ReportDeliveryMethod enums).
--
-- 2. Add timezone + run_history so the core scheduler has parity with the former
--    commercial one: cron is evaluated in the job's IANA zone (null = UTC) and
--    each run appends to a bounded JSON timeline.

ALTER TABLE scheduled_report_jobs DROP CONSTRAINT IF EXISTS chk_report_type;

ALTER TABLE scheduled_report_jobs ADD COLUMN timezone varchar(50);

ALTER TABLE scheduled_report_jobs
    ADD COLUMN run_history jsonb NOT NULL DEFAULT '[]'::jsonb;
