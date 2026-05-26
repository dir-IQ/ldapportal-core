-- Switch from 3-column to 6-column grid layout.
-- Migrate existing span values: 1->2, 2->4, 3->6.

UPDATE profile_attribute_configs SET column_span = CASE
    WHEN column_span = 1 THEN 2
    WHEN column_span = 2 THEN 4
    ELSE 6
END;

UPDATE profile_attribute_configs SET registration_column_span = CASE
    WHEN registration_column_span = 1 THEN 2
    WHEN registration_column_span = 2 THEN 4
    WHEN registration_column_span = 3 THEN 6
    ELSE registration_column_span
END WHERE registration_column_span IS NOT NULL;

UPDATE profile_attribute_configs SET self_service_column_span = CASE
    WHEN self_service_column_span = 1 THEN 2
    WHEN self_service_column_span = 2 THEN 4
    WHEN self_service_column_span = 3 THEN 6
    ELSE self_service_column_span
END WHERE self_service_column_span IS NOT NULL;

-- Make registration/self-service layout columns nullable (null = inherit from admin).
ALTER TABLE profile_attribute_configs
    ALTER COLUMN registration_column_span DROP NOT NULL,
    ALTER COLUMN registration_column_span SET DEFAULT NULL,
    ALTER COLUMN registration_display_order DROP NOT NULL,
    ALTER COLUMN registration_display_order SET DEFAULT NULL,
    ALTER COLUMN self_service_column_span DROP NOT NULL,
    ALTER COLUMN self_service_column_span SET DEFAULT NULL,
    ALTER COLUMN self_service_display_order DROP NOT NULL,
    ALTER COLUMN self_service_display_order SET DEFAULT NULL;

-- Set existing default values back to NULL to enable inheritance.
UPDATE profile_attribute_configs
    SET registration_column_span = NULL,
        registration_display_order = NULL,
        registration_section_name = NULL
    WHERE registration_section_name = '' OR registration_section_name IS NULL;

UPDATE profile_attribute_configs
    SET self_service_column_span = NULL,
        self_service_display_order = NULL,
        self_service_section_name = NULL
    WHERE self_service_section_name = '' OR self_service_section_name IS NULL;

-- Update default for column_span to 6.
ALTER TABLE profile_attribute_configs ALTER COLUMN column_span SET DEFAULT 6;
