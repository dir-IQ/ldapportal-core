-- Add changelog format discriminator so each audit source can declare whether it
-- uses Oracle DSEE cn=changelog or OpenLDAP slapo-accesslog.
ALTER TABLE audit_data_sources
    ADD COLUMN changelog_format VARCHAR(25) NOT NULL DEFAULT 'DSEE_CHANGELOG';

ALTER TABLE audit_data_sources
    ADD CONSTRAINT chk_changelog_format
    CHECK (changelog_format IN ('DSEE_CHANGELOG', 'OPENLDAP_ACCESSLOG'));
