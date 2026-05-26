-- Entra ID connection fields on directory_connections.
-- These are nullable because LDAP directories don't use them.
ALTER TABLE directory_connections ADD COLUMN tenant_id VARCHAR(100);
ALTER TABLE directory_connections ADD COLUMN entra_client_id VARCHAR(100);
ALTER TABLE directory_connections ADD COLUMN entra_client_secret_encrypted TEXT;
ALTER TABLE directory_connections ADD COLUMN graph_endpoint VARCHAR(255) DEFAULT 'https://graph.microsoft.com';

-- For Entra ID directories, LDAP-specific fields (host, port, bindDn, etc.)
-- remain in the table but are unused. The directoryType column distinguishes
-- which fields are relevant.
