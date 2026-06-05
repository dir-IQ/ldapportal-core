-- SPDX-License-Identifier: Apache-2.0
--
-- Per-directory, operator-configurable LDAP objectClass sets that identify
-- user vs group entries. Previously these were hardcoded (and had drifted)
-- across LdifPreviewService, the dashboard services, and the group search
-- filter. They now live as vendor defaults in DirectoryObjectClassDefaults
-- and may be overridden per connection.
--
-- Columns are nullable: an empty/null value means "use the vendor default
-- for this directory_type" (resolved at read time). Existing rows are
-- backfilled with the vendor default so the configured value is visible in
-- the UI and stable across IaC re-applies. Stored comma-delimited; the
-- conventional camelCase spelling is preserved for display.

ALTER TABLE directory_connections
    ADD COLUMN user_object_classes  TEXT,
    ADD COLUMN group_object_classes TEXT;

UPDATE directory_connections
SET user_object_classes = CASE directory_type
        WHEN 'ACTIVE_DIRECTORY'          THEN 'user'
        WHEN 'ENTRA_ID'                  THEN 'user'
        WHEN 'OPENLDAP'                  THEN 'inetOrgPerson,organizationalPerson,person,posixAccount'
        WHEN 'IBM_DIRECTORY_SERVER'      THEN 'inetOrgPerson,organizationalPerson,person'
        WHEN 'ORACLE_UNIFIED_DIRECTORY'  THEN 'inetOrgPerson,organizationalPerson,person'
        ELSE 'inetOrgPerson,organizationalPerson,person,user,posixAccount'
    END,
    group_object_classes = CASE directory_type
        WHEN 'ACTIVE_DIRECTORY'          THEN 'group'
        WHEN 'ENTRA_ID'                  THEN 'group'
        WHEN 'OPENLDAP'                  THEN 'groupOfNames,groupOfUniqueNames,posixGroup'
        WHEN 'IBM_DIRECTORY_SERVER'      THEN 'groupOfNames,groupOfUniqueNames,groupOfURLs'
        WHEN 'ORACLE_UNIFIED_DIRECTORY'  THEN 'groupOfNames,groupOfUniqueNames,groupOfURLs'
        ELSE 'groupOfNames,groupOfUniqueNames,posixGroup,group,groupOfURLs'
    END;
