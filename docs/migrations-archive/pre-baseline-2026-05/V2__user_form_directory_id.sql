-- Add directory_id FK to user_form so each form is scoped to a directory connection.
ALTER TABLE user_form
    ADD COLUMN directory_id UUID;

ALTER TABLE user_form
    ADD CONSTRAINT fk_user_form_directory
        FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE SET NULL;

CREATE INDEX idx_user_form_directory ON user_form (directory_id);
