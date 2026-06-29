-- SPDX-License-Identifier: Apache-2.0
-- Optional per-profile UI theme colour, stored as a #RRGGBB hex string. Nullable:
-- NULL means the profile has no theme, so the admin user/group list pages and the
-- new/edit user/group modals render their default header styling (profile name in
-- blue). When set, those headers show a band of this colour instead. Purely
-- presentational — no effect on provisioning behaviour, so existing rows are
-- unchanged.
ALTER TABLE provisioning_profiles
    ADD COLUMN theme_color varchar(7);
