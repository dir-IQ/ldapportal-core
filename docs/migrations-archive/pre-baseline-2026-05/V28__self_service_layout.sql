-- Flag to control whether an attribute appears on the self-registration form
-- (separate from self_service_edit which controls the profile-edit form).
ALTER TABLE profile_attribute_configs
    ADD COLUMN IF NOT EXISTS self_registration_edit BOOLEAN NOT NULL DEFAULT FALSE;

-- Separate layout properties for the self-service profile-edit form.
ALTER TABLE profile_attribute_configs
    ADD COLUMN IF NOT EXISTS self_service_section_name  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS self_service_column_span   INT NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS self_service_display_order INT NOT NULL DEFAULT 0;
