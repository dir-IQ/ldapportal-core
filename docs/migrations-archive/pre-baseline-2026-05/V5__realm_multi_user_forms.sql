-- Remove primary/auxiliary objectclass attributes from realms.
-- User forms now carry the objectClass; a realm can link to many user forms.

ALTER TABLE realms DROP COLUMN primary_user_objectclass;

DROP TABLE IF EXISTS realm_auxiliary_objectclasses;
