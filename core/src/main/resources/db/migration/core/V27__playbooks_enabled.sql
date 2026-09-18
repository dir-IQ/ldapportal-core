-- SPDX-License-Identifier: Apache-2.0
-- Global on/off switch for the Lifecycle Playbooks feature, surfaced as a
-- checkbox under Settings → User/Group Edits. When off, the playbook nav
-- links, views and the "Run playbook" user action are hidden and the
-- /playbooks API refuses requests. Defaults true so existing installs keep
-- the behaviour they had at upgrade time; playbook definitions and execution
-- history are preserved while the feature is off.

ALTER TABLE application_settings
    ADD COLUMN playbooks_enabled boolean DEFAULT true NOT NULL;
