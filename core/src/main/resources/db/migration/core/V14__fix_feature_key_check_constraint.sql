-- SPDX-License-Identifier: Apache-2.0
-- Realign admin_feature_permissions.feature_key with the FeatureKey enum.
--
-- The baseline chk_feature_key allow-list had drifted from the enum: it was
-- missing the access_review / sod / hr / auditor features, so toggling those
-- overrides in the "What can they do?" editor failed with
--   new row ... violates check constraint "chk_feature_key"
-- It also carried a phantom 'reports.export' that is not a FeatureKey (the enum
-- has reports.run / reports.schedule only) — no row can ever hold it, so it is
-- dropped here to keep the constraint an exact mirror of the enum.

ALTER TABLE admin_feature_permissions DROP CONSTRAINT IF EXISTS chk_feature_key;

ALTER TABLE admin_feature_permissions ADD CONSTRAINT chk_feature_key
    CHECK (feature_key IN (
        'user.create',
        'user.edit',
        'user.delete',
        'user.enable_disable',
        'user.move',
        'user.reset_password',
        'user.read',
        'group.edit',
        'group.manage_members',
        'group.create_delete',
        'group.read',
        'bulk.import',
        'bulk.export',
        'bulk.attribute_update',
        'bulk.delete',
        'reports.run',
        'reports.schedule',
        'access_review.manage',
        'access_review.review',
        'playbook.manage',
        'playbook.execute',
        'approval.manage',
        'csv_template.manage',
        'directory.browse',
        'schema.read',
        'sod.manage',
        'sod.view',
        'hr.manage',
        'hr.view',
        'auditor.manage'
    ));
