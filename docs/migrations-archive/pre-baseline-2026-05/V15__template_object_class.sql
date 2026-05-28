-- Add objectClass column to CSV mapping templates
ALTER TABLE csv_mapping_templates ADD COLUMN object_class VARCHAR(255);
