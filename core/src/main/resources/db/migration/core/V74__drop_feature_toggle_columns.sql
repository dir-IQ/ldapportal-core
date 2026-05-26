-- Drop the settings-derived feature-module toggles.
--
-- These columns (added in V62) only ever fed the legacy
-- SettingsLicenseProvider, which derived GOVERNANCE / HR_SYNC
-- entitlements from application settings when no signed license was
-- present. That provider is removed: entitlements now come solely from
-- a signed license JWT (FileLicenseProvider) or the edition baseline
-- (CommunityEditionLicenseProvider). Nothing reads these columns
-- anymore, so they are dropped.
ALTER TABLE application_settings DROP COLUMN hr_integration_enabled;
ALTER TABLE application_settings DROP COLUMN compliance_enabled;
