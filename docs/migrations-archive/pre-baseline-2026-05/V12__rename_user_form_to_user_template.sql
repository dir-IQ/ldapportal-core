-- Rename user_form tables and columns to user_template.

-- 1. Rename tables
ALTER TABLE user_form RENAME TO user_template;
ALTER TABLE user_form_attribute_config RENAME TO user_template_attribute_config;
ALTER TABLE user_form_object_classes RENAME TO user_template_object_classes;

-- 2. Rename columns: form_name -> template_name in user_template
ALTER TABLE user_template RENAME COLUMN form_name TO template_name;

-- 3. Rename FK columns: user_form_id -> user_template_id
ALTER TABLE user_template_attribute_config RENAME COLUMN user_form_id TO user_template_id;
ALTER TABLE user_template_object_classes RENAME COLUMN user_form_id TO user_template_id;
ALTER TABLE realm_objectclasses RENAME COLUMN user_form_id TO user_template_id;
