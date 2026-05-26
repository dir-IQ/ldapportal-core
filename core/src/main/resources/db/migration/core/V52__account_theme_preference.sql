-- User preferences: theme stored per-account instead of browser localStorage
ALTER TABLE accounts ADD COLUMN theme_preference VARCHAR(10) DEFAULT 'system';
