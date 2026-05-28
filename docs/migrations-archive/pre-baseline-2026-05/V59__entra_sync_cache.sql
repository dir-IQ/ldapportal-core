-- Local cache tables for Entra ID data.
-- Enables SoD scans, access reviews, and entitlement reporting without
-- hitting the Graph API on every request.

CREATE TABLE entra_users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id        UUID NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
    entra_object_id     VARCHAR(100) NOT NULL,
    display_name        VARCHAR(500),
    user_principal_name VARCHAR(500),
    mail                VARCHAR(500),
    department          VARCHAR(255),
    job_title           VARCHAR(255),
    employee_id         VARCHAR(255),
    account_enabled     BOOLEAN,
    synced_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(directory_id, entra_object_id)
);

CREATE TABLE entra_groups (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id        UUID NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
    entra_object_id     VARCHAR(100) NOT NULL,
    display_name        VARCHAR(500),
    description         TEXT,
    synced_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(directory_id, entra_object_id)
);

CREATE TABLE entra_group_memberships (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id        UUID NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
    user_object_id      VARCHAR(100) NOT NULL,
    group_object_id     VARCHAR(100) NOT NULL,
    synced_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(directory_id, user_object_id, group_object_id)
);

CREATE TABLE entra_sync_state (
    directory_id        UUID PRIMARY KEY REFERENCES directory_connections(id) ON DELETE CASCADE,
    user_delta_token    TEXT,
    group_delta_token   TEXT,
    audit_last_poll     TIMESTAMPTZ,
    last_full_sync      TIMESTAMPTZ
);

CREATE INDEX idx_entra_users_dir ON entra_users(directory_id);
CREATE INDEX idx_entra_groups_dir ON entra_groups(directory_id);
CREATE INDEX idx_entra_memberships_dir ON entra_group_memberships(directory_id);
CREATE INDEX idx_entra_memberships_user ON entra_group_memberships(directory_id, user_object_id);
CREATE INDEX idx_entra_memberships_group ON entra_group_memberships(directory_id, group_object_id);
