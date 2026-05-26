-- ============================================================================
-- V25: Replace Realm + UserTemplate model with Provisioning Profiles
-- ============================================================================

-- 1. New tables ---------------------------------------------------------------

CREATE TABLE provisioning_profiles (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id              UUID NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
    name                      VARCHAR(255) NOT NULL,
    description               TEXT,
    target_ou_dn              VARCHAR(500) NOT NULL,
    rdn_attribute             VARCHAR(100) NOT NULL,
    show_dn_field             BOOLEAN NOT NULL DEFAULT true,
    enabled                   BOOLEAN NOT NULL DEFAULT true,
    self_registration_allowed BOOLEAN NOT NULL DEFAULT false,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(directory_id, name)
);

CREATE TABLE profile_object_classes (
    profile_id        UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    object_class_name VARCHAR(255) NOT NULL
);

CREATE TABLE profile_attribute_configs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id          UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    attribute_name      VARCHAR(100) NOT NULL,
    custom_label        VARCHAR(255),
    input_type          VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    required_on_create  BOOLEAN NOT NULL DEFAULT false,
    editable_on_create  BOOLEAN NOT NULL DEFAULT true,
    editable_on_update  BOOLEAN NOT NULL DEFAULT true,
    self_service_edit   BOOLEAN NOT NULL DEFAULT false,
    default_value       VARCHAR(500),
    computed_expression VARCHAR(500),
    validation_regex    VARCHAR(500),
    validation_message  VARCHAR(255),
    allowed_values      TEXT,
    min_length          INTEGER,
    max_length          INTEGER,
    section_name        VARCHAR(100),
    column_span         INTEGER NOT NULL DEFAULT 3,
    display_order       INTEGER NOT NULL DEFAULT 0,
    hidden              BOOLEAN NOT NULL DEFAULT false,
    UNIQUE(profile_id, attribute_name)
);

CREATE TABLE profile_group_assignments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id       UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    group_dn         VARCHAR(500) NOT NULL,
    member_attribute VARCHAR(50) NOT NULL DEFAULT 'member',
    display_order    INTEGER NOT NULL DEFAULT 0,
    UNIQUE(profile_id, group_dn)
);

CREATE TABLE profile_lifecycle_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id              UUID NOT NULL UNIQUE REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    expires_after_days      INTEGER,
    max_renewals            INTEGER,
    renewal_days            INTEGER,
    on_expiry_action        VARCHAR(20) NOT NULL DEFAULT 'DISABLE',
    on_expiry_move_dn       VARCHAR(500),
    on_expiry_remove_groups BOOLEAN NOT NULL DEFAULT true,
    on_expiry_notify        BOOLEAN NOT NULL DEFAULT true,
    warning_days_before     INTEGER,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE profile_approval_configs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id            UUID NOT NULL UNIQUE REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    require_approval      BOOLEAN NOT NULL DEFAULT false,
    approver_mode         VARCHAR(20) NOT NULL DEFAULT 'DATABASE',
    approver_group_dn     VARCHAR(500),
    auto_escalate_days    INTEGER,
    escalation_account_id UUID REFERENCES accounts(id)
);

CREATE TABLE profile_approvers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id       UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    admin_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    UNIQUE(profile_id, admin_account_id)
);

CREATE TABLE admin_profile_roles (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    profile_id       UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    base_role        VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(admin_account_id, profile_id)
);

-- 2. Update pending_approvals: add profile_id, drop realm_id -----------------

ALTER TABLE pending_approvals DROP CONSTRAINT IF EXISTS fk_pa_realm;
DROP INDEX IF EXISTS idx_pa_realm_status;
ALTER TABLE pending_approvals ADD COLUMN profile_id UUID REFERENCES provisioning_profiles(id);
ALTER TABLE pending_approvals DROP COLUMN IF EXISTS realm_id;

-- 3. Drop old tables ----------------------------------------------------------

DROP TABLE IF EXISTS realm_approvers CASCADE;
DROP TABLE IF EXISTS realm_settings CASCADE;
DROP TABLE IF EXISTS realm_objectclasses CASCADE;
DROP TABLE IF EXISTS admin_realm_roles CASCADE;
DROP TABLE IF EXISTS realms CASCADE;
DROP TABLE IF EXISTS user_template_attribute_config CASCADE;
DROP TABLE IF EXISTS user_template_object_classes CASCADE;
DROP TABLE IF EXISTS user_template CASCADE;
