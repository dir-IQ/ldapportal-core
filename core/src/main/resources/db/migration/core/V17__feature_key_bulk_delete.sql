-- SPDX-License-Identifier: Apache-2.0
--
-- Adds the bulk.delete feature key to the admin_feature_permissions
-- CHECK constraint. The FeatureKey enum gained BULK_DELETE ("bulk.delete")
-- for the CSV-driven bulk user delete; without this the constraint
-- (chk_feature_key) rejects any attempt to persist a per-profile or
-- admin-wide override row for the key, so the feature can never be
-- explicitly granted/denied even though a default ADMIN can use it.
--
-- Forward-only, additive: the constraint is dropped and re-created with
-- the full enumerated set plus the new key. chk_afp_feature_key is dropped
-- defensively (a stray second constraint that some older installs carried)
-- so every database converges on a single chk_feature_key.

ALTER TABLE admin_feature_permissions
    DROP CONSTRAINT IF EXISTS chk_feature_key;
ALTER TABLE admin_feature_permissions
    DROP CONSTRAINT IF EXISTS chk_afp_feature_key;

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
        'bulk.delete',
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
        -- directory / schema
        'directory.browse',
        'schema.read'
    ));
