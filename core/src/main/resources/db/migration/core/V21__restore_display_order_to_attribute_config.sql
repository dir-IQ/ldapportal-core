-- Restore display_order column (dropped in V10) so the layout designer's
-- field ordering is persisted across save/reload cycles.
ALTER TABLE user_template_attribute_config
    ADD COLUMN display_order INT NOT NULL DEFAULT 0;
