-- Optional per-profile DN template for the admin new-user form.
--
-- NULL preserves the historical default — the entry DN is composed as
-- "<rdnAttribute>=<rdnValue>,<targetUserDn>". When set, the value is a
-- ${attr} expression (same engine as attribute computed-expressions), e.g.
-- "uid=${uid},ou=people,dc=example,dc=com", used to seed the (now editable)
-- DN field on create. Any submitted DN is still validated server-side to
-- remain within the profile's target_user_dn subtree.
ALTER TABLE provisioning_profiles ADD COLUMN dn_template VARCHAR(500);
