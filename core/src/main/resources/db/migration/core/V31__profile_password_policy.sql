-- Profile-level password generation settings
ALTER TABLE provisioning_profiles
    ADD COLUMN password_length          INT     NOT NULL DEFAULT 16,
    ADD COLUMN password_uppercase       BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN password_lowercase       BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN password_digits          BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN password_special         BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN password_special_chars   VARCHAR(50) NOT NULL DEFAULT '!@#$%^&*',
    ADD COLUMN email_password_to_user   BOOLEAN NOT NULL DEFAULT false;
