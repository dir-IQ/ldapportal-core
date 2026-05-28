-- Store provisioning error message when LDAP operation fails after approval
ALTER TABLE pending_approvals
    ADD COLUMN provision_error TEXT;
