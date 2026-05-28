-- Repair admin_feature_permissions.chk_feature_key for pre-existing
-- deployments. No-op-equivalent for fresh installs.
--
-- Background: before the Phase 4 follow-up (commit 3446269) the core
-- V1 / V18 migrations created a chk_feature_key constraint that
-- enumerated only a subset of the core FeatureKey enum values; seven
-- core keys (user.read, group.read, playbook.*, approval.manage,
-- csv_template.manage, directory.browse, schema.read) were never
-- added. Worse, the ee governance / HR migrations added a second,
-- differently-named constraint (chk_afp_feature_key) containing the
-- full set and never dropped the core one, so installs running
-- against real PostgreSQL carried both constraints and rejected
-- inserts of any ee-only key.
--
-- The Phase 4 follow-up rewrote V18 / V100 / V104 / V201 in place to
-- fix the issue for fresh installs (they now all use a single
-- unified chk_feature_key). But Flyway won't re-execute already-
-- applied migrations, so existing databases still carry the old
-- constraint state.
--
-- This migration re-establishes the constraint from scratch at a
-- fresh version number, so every database — old or new — converges
-- to the same state on next boot. Each ee module then has a paired
-- follow-up (ee/governance/V113, ee/hr/V203) that extends the
-- constraint with its keys.

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
