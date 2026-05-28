-- Update the admin_feature_permissions.chk_feature_key CHECK constraint to
-- include every core feature key. Previously this constraint only enumerated
-- the initial set (user/group/bulk/reports) and every ee migration added its
-- own *second* constraint (chk_afp_feature_key) with the full list — so in
-- commercial both constraints coexisted and any ee-only key was rejected by
-- the unchanged core one. Worse, several core features added over time
-- (user.read, group.read, playbook.*, approval.manage, csv_template.manage,
-- directory.browse, schema.read) were never added back to the core
-- constraint, so in a community build (no ee migrations) those insertions
-- would fail.
--
-- This migration establishes chk_feature_key as the single, authoritative
-- constraint for the core feature key set. Each ee migration extends it
-- (drop-and-recreate) with its own keys, using the SAME constraint name
-- rather than declaring a parallel constraint — see V100, V104, V201.

ALTER TABLE admin_feature_permissions
    DROP CONSTRAINT chk_feature_key;

ALTER TABLE admin_feature_permissions
    ADD CONSTRAINT chk_feature_key CHECK (feature_key IN (
        -- user lifecycle
        'user.create',
        'user.edit',
        'user.delete',
        'user.enable_disable',
        'user.move',
        'user.reset_password',
        'user.read',
        -- group lifecycle
        'group.edit',
        'group.manage_members',
        'group.create_delete',
        'group.read',
        -- bulk
        'bulk.import',
        'bulk.export',
        'bulk.attribute_update',
        -- reports
        'reports.run',
        'reports.export',
        'reports.schedule',
        -- playbooks
        'playbook.manage',
        'playbook.execute',
        -- approval
        'approval.manage',
        -- csv mapping templates
        'csv_template.manage',
        -- directory + schema read
        'directory.browse',
        'schema.read'
    ));
