-- Separate layout properties for the self-registration form.
-- These allow admins to arrange the registration form independently
-- of the admin user-creation form.

ALTER TABLE profile_attribute_configs
    ADD COLUMN registration_section_name  VARCHAR(100),
    ADD COLUMN registration_column_span   INT NOT NULL DEFAULT 3,
    ADD COLUMN registration_display_order INT NOT NULL DEFAULT 0;
