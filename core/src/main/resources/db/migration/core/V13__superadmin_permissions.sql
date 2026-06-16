-- System-scoped permission grants for SUPERADMIN accounts.
--
-- Counterpart to the directory-scoped admin_feature_permissions: these gate
-- system-wide superadmin operations (manage application accounts, application
-- settings, directories, …). A row's presence = the permission is granted.
--
-- Owner model: a superadmin holding 'superadmin.manage_superadmins' is a full
-- owner (treated as holding every permission and the only one allowed to edit
-- other superadmins' permissions).

CREATE TABLE superadmin_permission_grants (
    id          uuid DEFAULT gen_random_uuid() NOT NULL,
    account_id  uuid NOT NULL,
    permission  character varying(100) NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT superadmin_permission_grants_pkey PRIMARY KEY (id),
    CONSTRAINT fk_superadmin_perm_account FOREIGN KEY (account_id)
        REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT uq_superadmin_permission UNIQUE (account_id, permission)
);

CREATE INDEX idx_superadmin_perm_account ON superadmin_permission_grants (account_id);

-- Backfill: make every existing superadmin a full owner so the upgrade is a
-- zero-behaviour change (superadmins remain omnipotent). Operators can then
-- scope individual accounts back from the UI.
INSERT INTO superadmin_permission_grants (account_id, permission)
SELECT a.id, 'superadmin.manage_superadmins'
FROM accounts a
WHERE a.role = 'SUPERADMIN';
