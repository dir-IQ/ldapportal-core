-- V6: Allow multiple objectClasses per user form.
-- Moves the single object_class_name column into a new collection table.

CREATE TABLE user_form_object_classes (
    user_form_id       UUID         NOT NULL,
    object_class_name  VARCHAR(255) NOT NULL,
    CONSTRAINT fk_ufoc_user_form FOREIGN KEY (user_form_id) REFERENCES user_form (id) ON DELETE CASCADE
);

-- Migrate existing data
INSERT INTO user_form_object_classes (user_form_id, object_class_name)
SELECT id, object_class_name FROM user_form WHERE object_class_name IS NOT NULL;

-- Drop the old column
ALTER TABLE user_form DROP COLUMN object_class_name;
