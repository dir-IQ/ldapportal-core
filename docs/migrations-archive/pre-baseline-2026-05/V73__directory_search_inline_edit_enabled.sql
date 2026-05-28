-- Add directory_search_inline_edit_enabled to application_settings.
--
-- Toggles the inline-edit affordance on the Directory Search results
-- table. When disabled, the "Edit results" button is hidden in the
-- UI; the underlying typed update endpoints stay available for any
-- other caller (Terraform, scripts, the per-entity edit flows).
--
-- Defaults to TRUE: the inline edit feature ships enabled in the
-- same release as this migration, so existing installs preserve the
-- behaviour they got at upgrade time. Operators who want to gate
-- the feature flip the flag in Settings → User/Group Edits.

ALTER TABLE application_settings
    ADD COLUMN directory_search_inline_edit_enabled BOOLEAN NOT NULL DEFAULT TRUE;
