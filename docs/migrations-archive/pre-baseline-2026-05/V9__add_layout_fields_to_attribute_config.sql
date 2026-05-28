-- Add layout fields for the form layout designer
ALTER TABLE user_form_attribute_config
    ADD COLUMN section_name VARCHAR(255),
    ADD COLUMN column_span  INTEGER NOT NULL DEFAULT 3;
