-- Optional DN-from-column override for CSV import templates.
-- When set, the importer reads each entry's full DN verbatim from the named CSV
-- column instead of constructing it from the RDN (key) attribute + parent DN.
-- NULL preserves the existing construct-from-RDN behaviour.
ALTER TABLE csv_mapping_templates
    ADD COLUMN dn_source_column varchar(255);
