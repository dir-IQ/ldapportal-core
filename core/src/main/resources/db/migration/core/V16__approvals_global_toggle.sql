-- SPDX-License-Identifier: Apache-2.0
-- Global approval toggles. Both default TRUE so existing installs keep the
-- approval behaviour they had at upgrade time. These are master switches that
-- override (but never mutate) per-profile requireApproval settings:
--   approvals_enabled                  → admin-initiated user/group operations
--   self_registration_approval_enabled → self-service registration only
ALTER TABLE application_settings
    ADD COLUMN approvals_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE application_settings
    ADD COLUMN self_registration_approval_enabled BOOLEAN NOT NULL DEFAULT TRUE;
