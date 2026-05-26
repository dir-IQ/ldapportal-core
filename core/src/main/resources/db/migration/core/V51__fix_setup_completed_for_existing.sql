-- Fix: existing application_settings rows predate the setup_completed column,
-- so they represent already-configured instances. Set them to true.
UPDATE application_settings SET setup_completed = TRUE WHERE setup_completed = FALSE;
