-- Add skip_header_row option to CSV mapping templates
ALTER TABLE csv_mapping_templates ADD COLUMN skip_header_row BOOLEAN NOT NULL DEFAULT TRUE;
