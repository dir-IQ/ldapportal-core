-- Per-template field delimiter for CSV imports. Defaults to comma so existing
-- templates keep parsing exactly as before; operators can pick another single
-- character (semicolon, tab, pipe, ...) for files exported with a different
-- separator.
ALTER TABLE csv_mapping_templates
    ADD COLUMN field_delimiter varchar(1) NOT NULL DEFAULT ',';
