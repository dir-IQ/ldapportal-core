-- User preferences: display density stored per-account so the choice
-- follows the user across browsers and devices, like theme_preference
-- (added in V52). Default 'comfortable' matches the frontend's
-- useDensity.ts default for new sessions on a brand-new browser.
ALTER TABLE accounts ADD COLUMN density_preference VARCHAR(15) DEFAULT 'comfortable';
