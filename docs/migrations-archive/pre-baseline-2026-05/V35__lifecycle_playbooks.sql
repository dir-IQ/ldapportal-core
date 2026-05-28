-- Lifecycle playbooks: multi-step onboarding/offboarding workflows

CREATE TABLE lifecycle_playbooks (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    directory_id      UUID         NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
    profile_id        UUID         REFERENCES provisioning_profiles(id) ON DELETE SET NULL,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    type              VARCHAR(20)  NOT NULL,
    require_approval  BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_playbook_dir_name UNIQUE (directory_id, name),
    CONSTRAINT chk_playbook_type CHECK (type IN ('ONBOARD', 'OFFBOARD', 'CUSTOM'))
);

CREATE TABLE playbook_steps (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    playbook_id       UUID         NOT NULL REFERENCES lifecycle_playbooks(id) ON DELETE CASCADE,
    step_order        INTEGER      NOT NULL,
    action            VARCHAR(30)  NOT NULL,
    parameters        JSONB        NOT NULL DEFAULT '{}',
    continue_on_error BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_step_action CHECK (action IN (
        'ADD_TO_GROUP', 'REMOVE_FROM_GROUP', 'REMOVE_ALL_GROUPS',
        'SET_ATTRIBUTE', 'REMOVE_ATTRIBUTE',
        'MOVE_OU', 'DISABLE', 'ENABLE', 'DELETE', 'NOTIFY'
    ))
);

CREATE TABLE playbook_executions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    playbook_id   UUID         NOT NULL REFERENCES lifecycle_playbooks(id) ON DELETE CASCADE,
    target_dn     VARCHAR(500) NOT NULL,
    executed_by   UUID         REFERENCES accounts(id),
    status        VARCHAR(20)  NOT NULL,
    step_results  JSONB        NOT NULL DEFAULT '[]',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT chk_exec_status CHECK (status IN ('SUCCESS', 'PARTIAL', 'FAILED', 'ROLLED_BACK'))
);

CREATE INDEX idx_playbook_dir ON lifecycle_playbooks (directory_id);
CREATE INDEX idx_exec_playbook ON playbook_executions (playbook_id);
