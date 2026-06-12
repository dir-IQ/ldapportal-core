-- SPDX-License-Identifier: Apache-2.0
-- Layout of the read-only DN field on the create form, so an admin can move and
-- resize it in the form designer. All nullable: NULL reproduces the historical
-- default (the DN sits immediately after the RDN at 2/3 width), so existing
-- profiles are unchanged. dn_column_span is a 1-6 grid span; dn_section_name +
-- dn_display_order place the DN among the form's fields.
ALTER TABLE provisioning_profiles
    ADD COLUMN dn_column_span integer,
    ADD COLUMN dn_section_name varchar(100),
    ADD COLUMN dn_display_order integer;
