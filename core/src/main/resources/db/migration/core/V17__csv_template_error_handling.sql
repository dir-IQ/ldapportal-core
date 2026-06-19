-- Per-template choice of how a bulk import reacts to rows with errors:
-- SKIP_ERRORS (default, legacy behaviour) skips bad rows and imports the rest;
-- ABORT_ON_ERROR blocks the whole import until every row is valid.
ALTER TABLE csv_mapping_templates
    ADD COLUMN error_handling VARCHAR(20) NOT NULL DEFAULT 'SKIP_ERRORS';
