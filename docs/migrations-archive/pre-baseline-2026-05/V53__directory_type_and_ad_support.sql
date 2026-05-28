-- Directory type for AD-specific behavior (password reset, nested groups, etc.)
ALTER TABLE directory_connections
    ADD COLUMN directory_type VARCHAR(20) NOT NULL DEFAULT 'GENERIC';

-- Secondary DC for failover
ALTER TABLE directory_connections
    ADD COLUMN secondary_host VARCHAR(255),
    ADD COLUMN secondary_port INTEGER;

-- Global Catalog port for forest-wide AD searches
ALTER TABLE directory_connections
    ADD COLUMN global_catalog_port INTEGER;

-- AD DirSync changelog format
-- (ChangelogFormat enum extended in code; no table change needed)

-- DirSync cookie storage on audit_data_sources
ALTER TABLE audit_data_sources
    ADD COLUMN dirsync_cookie BYTEA;
