ALTER TABLE application_settings
    ADD COLUMN superadmin_bypass_approval BOOLEAN NOT NULL DEFAULT FALSE;
