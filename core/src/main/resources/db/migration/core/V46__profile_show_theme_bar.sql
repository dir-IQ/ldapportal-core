-- SPDX-License-Identifier: Apache-2.0
--
-- Whether a provisioning profile's user and group dialogs show its theme
-- colour as a strip down their left edge (only when a theme colour is set).
ALTER TABLE provisioning_profiles ADD COLUMN show_theme_bar boolean NOT NULL DEFAULT true;
