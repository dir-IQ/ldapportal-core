-- Approval workflow: realm settings, realm approvers, and pending approvals

CREATE TABLE realm_settings (
    id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    realm_id  UUID         NOT NULL,
    key       VARCHAR(100) NOT NULL,
    value     VARCHAR(500) NOT NULL,
    CONSTRAINT pk_realm_settings PRIMARY KEY (id),
    CONSTRAINT uq_realm_setting  UNIQUE (realm_id, key),
    CONSTRAINT fk_rs_realm FOREIGN KEY (realm_id) REFERENCES realms (id) ON DELETE CASCADE
);

CREATE TABLE realm_approvers (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    realm_id         UUID        NOT NULL,
    admin_account_id UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_realm_approvers  PRIMARY KEY (id),
    CONSTRAINT uq_realm_approver   UNIQUE (realm_id, admin_account_id),
    CONSTRAINT fk_ra_realm  FOREIGN KEY (realm_id)         REFERENCES realms (id)    ON DELETE CASCADE,
    CONSTRAINT fk_ra_admin  FOREIGN KEY (admin_account_id) REFERENCES accounts (id)  ON DELETE CASCADE
);

CREATE TABLE pending_approvals (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    directory_id    UUID         NOT NULL,
    realm_id        UUID         NOT NULL,
    requested_by    UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    request_type    VARCHAR(30)  NOT NULL,
    payload         JSONB        NOT NULL,
    reject_reason   TEXT,
    reviewed_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_at     TIMESTAMPTZ,
    CONSTRAINT pk_pending_approvals PRIMARY KEY (id),
    CONSTRAINT fk_pa_directory FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE CASCADE,
    CONSTRAINT fk_pa_realm     FOREIGN KEY (realm_id)     REFERENCES realms (id)               ON DELETE CASCADE,
    CONSTRAINT fk_pa_requester FOREIGN KEY (requested_by) REFERENCES accounts (id),
    CONSTRAINT fk_pa_reviewer  FOREIGN KEY (reviewed_by)  REFERENCES accounts (id),
    CONSTRAINT chk_pa_status   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_pa_realm_status ON pending_approvals (realm_id, status);
