-- SPDX-License-Identifier: Apache-2.0
-- Drop chk_report_type so the scheduler can use the dynamic report-type set.
--
-- The schedulable report-type set is dynamic — built-in OperationalReportTypes,
-- addon OperationalReportProvider ids, and ee compliance types — so a baseline
-- allow-list can neither enumerate it nor stay in sync (the same dual-ownership
-- drift that retired chk_feature_key in V18). report_type is validated in the
-- service against the provider registry. The output_format / delivery_method
-- CHECKs stay (those value sets are stable, core-owned via the
-- ReportOutputFormat / ReportDeliveryMethod enums).
--
-- The timezone + run_history parity columns are added separately in
-- V400__report_jobs_parity_columns.sql, which is ordered AFTER the ee migration
-- bands on purpose — see that file for why.

ALTER TABLE scheduled_report_jobs DROP CONSTRAINT IF EXISTS chk_report_type;
