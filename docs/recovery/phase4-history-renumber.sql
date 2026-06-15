-- =====================================================================
-- Phase 4 follow-up: reconcile flyway_schema_history with per-module
-- migration renumbering.
--
-- Run ONCE against each database that was populated before the Phase 4
-- migration reorganisation (commit 8e95405, 2026-04). After this script
-- and an app restart, Flyway's repairThenMigrate bean will realign
-- checksums with the current migration files and the application will
-- boot normally.
--
-- What Phase 4 did:
--   * Moved ee migrations into per-module folders.
--   * Renumbered them into dedicated ranges so "add an ee module to
--     an existing install" is a pure forward-migrate operation:
--        governance: V100..V199
--        hr:         V200..V299
--        alerting:   V300..V399
--
-- What this script does:
--   1. Deletes duplicate history entries for the renumbered ee
--      migrations. These typically appear as a second application of
--      the same version number (installed_rank beyond the original
--      range), produced by an earlier repairThenMigrate cycle that
--      saw the files "go missing" from their old location.
--   2. Renumbers the remaining first-occurrence history rows to match
--      Phase 4's per-module layout.
--
-- Safety properties:
--   * Idempotent — running it a second time is a no-op because every
--     WHERE clause keys on the old version number, which no longer
--     exists after the first run.
--   * Transactional — wraps in BEGIN / COMMIT.
--   * Does NOT touch application tables. Only flyway_schema_history.
--
-- Pre-conditions:
--   * Stop the app before running.
--   * Take a DB backup (standard caution).
--
-- Post-conditions:
--   * On next app boot, Flyway's built-in repair() updates checksums
--     to match the new files. No migrations execute.
--
-- =====================================================================

BEGIN;

-- Step 1 — remove the duplicate re-applications.
--
-- These rows exist because a previous repair cycle re-inserted
-- already-applied migrations under their old version numbers. We
-- identify them as: version belongs to the set of renumbered ee
-- migrations AND installed_rank is beyond the first 66 rows (where
-- the originals live). The exact rank threshold is forgiving — the
-- version filter is the real key.
DELETE FROM flyway_schema_history
WHERE installed_rank > 66
  AND version IN (
      '22', '23', '24',          -- access review (V100..V102)
      '40', '41', '42', '43',    -- sod policies / reminders / templates (V103..V106)
      '46', '47', '48', '49', '50',  -- sod enh / report enh / template uniq / drift (V107..V111)
      '54',                      -- auditor links (V112)
      '44', '45', '60',          -- hr (V200..V202)
      '57'                       -- alerting (V300)
  );

-- Step 2 — renumber the first-occurrence rows so they reference the
-- new module-scoped version numbers. After these UPDATEs,
-- flyway_schema_history matches what the new JAR ships.
--
-- description and type columns don't need changing here; Flyway's
-- repair() on the next boot will realign them (and the checksums) to
-- the current file contents.

-- Governance (V100..V199)
UPDATE flyway_schema_history SET version = '100', script = 'V100__access_review_campaigns.sql'      WHERE version = '22';
UPDATE flyway_schema_history SET version = '101', script = 'V101__access_review_recurrence.sql'     WHERE version = '23';
UPDATE flyway_schema_history SET version = '102', script = 'V102__rename_draft_to_upcoming.sql'     WHERE version = '24';
UPDATE flyway_schema_history SET version = '103', script = 'V103__sod_policies.sql'                 WHERE version = '40';
UPDATE flyway_schema_history SET version = '104', script = 'V104__sod_feature_permissions.sql'      WHERE version = '41';
UPDATE flyway_schema_history SET version = '105', script = 'V105__campaign_reminder_tracking.sql'   WHERE version = '42';
UPDATE flyway_schema_history SET version = '106', script = 'V106__campaign_templates.sql'           WHERE version = '43';
UPDATE flyway_schema_history SET version = '107', script = 'V107__sod_enhancements.sql'             WHERE version = '46';
UPDATE flyway_schema_history SET version = '108', script = 'V108__report_enhancements.sql'          WHERE version = '47';
UPDATE flyway_schema_history SET version = '109', script = 'V109__campaign_template_unique_name.sql' WHERE version = '48';
UPDATE flyway_schema_history SET version = '110', script = 'V110__access_drift_detection.sql'       WHERE version = '49';
UPDATE flyway_schema_history SET version = '111', script = 'V111__access_drift_fixes.sql'           WHERE version = '50';
UPDATE flyway_schema_history SET version = '112', script = 'V112__auditor_links.sql'                WHERE version = '54';

-- HR (V200..V299)
UPDATE flyway_schema_history SET version = '200', script = 'V200__hr_integration.sql'               WHERE version = '44';
UPDATE flyway_schema_history SET version = '201', script = 'V201__hr_feature_permissions.sql'       WHERE version = '45';
UPDATE flyway_schema_history SET version = '202', script = 'V202__hr_entra_matching.sql'            WHERE version = '60';

-- Alerting (V300..V399)
UPDATE flyway_schema_history SET version = '300', script = 'V300__alert_rules_and_instances.sql'    WHERE version = '57';

-- Sanity check — expected counts after renumbering.
-- Should show 13 governance, 3 hr, 1 alerting rows.
DO $$
DECLARE
    gov_count INT;
    hr_count  INT;
    alt_count INT;
BEGIN
    SELECT COUNT(*) INTO gov_count FROM flyway_schema_history WHERE CAST(version AS INT) BETWEEN 100 AND 199;
    SELECT COUNT(*) INTO hr_count  FROM flyway_schema_history WHERE CAST(version AS INT) BETWEEN 200 AND 299;
    SELECT COUNT(*) INTO alt_count FROM flyway_schema_history WHERE CAST(version AS INT) BETWEEN 300 AND 399;

    IF gov_count <> 13 OR hr_count <> 3 OR alt_count <> 1 THEN
        RAISE EXCEPTION 'Unexpected renumbering result: governance=%, hr=%, alerting=% (expected 13, 3, 1)',
            gov_count, hr_count, alt_count;
    END IF;

    RAISE NOTICE 'Renumbering complete: governance=%, hr=%, alerting=%', gov_count, hr_count, alt_count;
END
$$;

COMMIT;
