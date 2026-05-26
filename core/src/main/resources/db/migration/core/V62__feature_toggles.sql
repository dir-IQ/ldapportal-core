-- Feature module toggles.
ALTER TABLE application_settings ADD COLUMN hr_integration_enabled BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE application_settings ADD COLUMN compliance_enabled BOOLEAN NOT NULL DEFAULT true;
