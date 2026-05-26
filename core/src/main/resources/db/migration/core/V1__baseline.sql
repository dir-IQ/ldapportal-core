-- V1: Baseline schema — consolidated from V1–V19.
--
-- Table creation order respects FK dependencies:
--   audit_data_sources, accounts, user_form
--     → directory_connections
--       → directory_user_base_dns, directory_group_base_dns
--       → realms
--         → realm_auxiliary_objectclasses
--         → realm_objectclasses (→ user_form)
--       → csv_mapping_templates → csv_mapping_template_entries
--       → scheduled_report_jobs (→ accounts)
--     → admin_realm_roles (→ accounts, realms)
--     → admin_branch_restrictions (→ accounts, realms)
--     → admin_feature_permissions (→ accounts)
--   application_settings (standalone)
--   audit_events (standalone)
--   user_form_attribute_config (→ user_form)

-- ─────────────────────────────────────────────────────────────────────────────
-- audit_data_sources
-- Separate LDAP changelog-reader connections (§8.1).
-- One audit source may serve multiple directory connections (1:N).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE audit_data_sources (
    id                      UUID          NOT NULL DEFAULT gen_random_uuid(),
    display_name            VARCHAR(255)  NOT NULL,
    host                    VARCHAR(255)  NOT NULL,
    port                    INTEGER       NOT NULL DEFAULT 389,
    ssl_mode                VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    trust_all_certs         BOOLEAN       NOT NULL DEFAULT FALSE,
    trusted_certificate_pem TEXT,
    bind_dn                 VARCHAR(1000) NOT NULL,
    -- AES-256 encrypted bind password; decryption key never stored in DB
    bind_password_encrypted TEXT          NOT NULL,
    -- Base DN for changelog reads (e.g. cn=changelog)
    changelog_base_dn       VARCHAR(1000) NOT NULL DEFAULT 'cn=changelog',
    -- Optional: restrict changelog reads to entries under this targetDN subtree
    branch_filter_dn        VARCHAR(1000),
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_audit_data_sources PRIMARY KEY (id),
    CONSTRAINT chk_audit_ssl_mode
        CHECK (ssl_mode IN ('NONE', 'LDAPS', 'STARTTLS'))
);

-- ─────────────────────────────────────────────────────────────────────────────
-- accounts
-- Unified account table for both superadmin and admin users.
-- role = SUPERADMIN → full system access
-- role = ADMIN      → scoped to assigned realms
-- auth_type = LOCAL → bcrypt password_hash stored here
-- auth_type = LDAP  → bind delegated to the global LDAP auth server
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE accounts (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    username      VARCHAR(255)  NOT NULL,
    display_name  VARCHAR(255),
    email         VARCHAR(255),
    role          VARCHAR(20)   NOT NULL,
    auth_type     VARCHAR(10)   NOT NULL DEFAULT 'LOCAL',
    -- bcrypt hash; NULL for LDAP-sourced accounts
    password_hash VARCHAR(255),
    -- Distinguished name in the LDAP directory (LDAP auth_type only)
    ldap_dn       VARCHAR(1000),
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_accounts           PRIMARY KEY (id),
    CONSTRAINT uq_account_username   UNIQUE (username),
    CONSTRAINT chk_account_role      CHECK (role      IN ('SUPERADMIN', 'ADMIN')),
    CONSTRAINT chk_account_auth_type CHECK (auth_type IN ('LOCAL', 'LDAP'))
);

CREATE INDEX idx_accounts_role   ON accounts (role);
CREATE INDEX idx_accounts_active ON accounts (active);

-- ─────────────────────────────────────────────────────────────────────────────
-- user_form
-- Reusable form definition associated with a specific LDAP objectClass.
-- Multiple forms may exist for the same objectClass (different field sets
-- for different realms), distinguished by form_name.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE user_form (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    object_class_name VARCHAR(255) NOT NULL,
    form_name         VARCHAR(255) NOT NULL,
    CONSTRAINT pk_user_form PRIMARY KEY (id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- user_form_attribute_config
-- Specifies how each LDAP attribute is presented in a user_form.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE user_form_attribute_config (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_form_id       UUID         NOT NULL,
    attribute_name     VARCHAR(255) NOT NULL,
    custom_label       VARCHAR(255),
    required_on_create BOOLEAN      NOT NULL DEFAULT FALSE,
    editable_on_create BOOLEAN      NOT NULL DEFAULT TRUE,
    input_type         VARCHAR(20)  NOT NULL DEFAULT 'TEXT',
    CONSTRAINT pk_user_form_attribute_config PRIMARY KEY (id),
    CONSTRAINT uq_user_form_attribute        UNIQUE (user_form_id, attribute_name),
    CONSTRAINT fk_ufac_user_form
        FOREIGN KEY (user_form_id) REFERENCES user_form (id) ON DELETE CASCADE,
    CONSTRAINT chk_ufac_input_type CHECK (input_type IN (
        'TEXT', 'TEXTAREA', 'PASSWORD', 'BOOLEAN',
        'DATE', 'DATETIME', 'MULTI_VALUE', 'DN_LOOKUP'
    ))
);

CREATE INDEX idx_ufac_user_form ON user_form_attribute_config (user_form_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- directory_connections
-- LDAP server connection configurations.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE directory_connections (
    id                            UUID          NOT NULL DEFAULT gen_random_uuid(),
    display_name                  VARCHAR(255)  NOT NULL,
    host                          VARCHAR(255)  NOT NULL,
    port                          INTEGER       NOT NULL DEFAULT 389,
    ssl_mode                      VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    -- Accept self-signed / untrusted certificates (development/test only)
    trust_all_certs               BOOLEAN       NOT NULL DEFAULT FALSE,
    -- PEM-encoded CA certificate for custom trust anchors
    trusted_certificate_pem       TEXT,
    -- Service account DN used by the application for LDAP operations
    bind_dn                       VARCHAR(1000) NOT NULL,
    -- Bind password encrypted with AES-256; key never stored in DB
    bind_password_encrypted       TEXT          NOT NULL,
    base_dn                       VARCHAR(1000) NOT NULL,
    -- LDAP paged results control page size
    paging_size                   INTEGER       NOT NULL DEFAULT 500,
    -- Connection pool settings
    pool_min_size                 INTEGER       NOT NULL DEFAULT 2,
    pool_max_size                 INTEGER       NOT NULL DEFAULT 20,
    pool_connect_timeout_seconds  INTEGER       NOT NULL DEFAULT 10,
    pool_response_timeout_seconds INTEGER       NOT NULL DEFAULT 30,
    -- ── Account enable/disable attribute config ───────────────────────────────
    -- LDAP attribute that represents enabled/disabled state
    enable_disable_attribute      VARCHAR(255),
    -- Whether the attribute is a boolean toggle or a string value
    enable_disable_value_type     VARCHAR(10)   DEFAULT 'STRING',
    -- Value to write when enabling the account
    enable_value                  VARCHAR(500),
    -- Value to write when disabling the account
    disable_value                 VARCHAR(500),
    -- ── Audit / changelog source ──────────────────────────────────────────────
    audit_data_source_id          UUID,
    enabled                       BOOLEAN       NOT NULL DEFAULT TRUE,
    -- ── Application user repository ───────────────────────────────────────────
    -- TRUE when this directory is the authoritative store for application users
    is_user_repository            BOOLEAN       NOT NULL DEFAULT FALSE,
    -- DN of the container in which new application user entries are created
    user_creation_base_dn         VARCHAR(1000),
    created_at                    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_directory_connections PRIMARY KEY (id),
    CONSTRAINT fk_dir_conn_audit_source
        FOREIGN KEY (audit_data_source_id)
        REFERENCES audit_data_sources (id) ON DELETE SET NULL,
    CONSTRAINT chk_dir_ssl_mode
        CHECK (ssl_mode IN ('NONE', 'LDAPS', 'STARTTLS')),
    CONSTRAINT chk_dir_enable_disable_value_type
        CHECK (enable_disable_value_type IN ('BOOLEAN', 'STRING'))
);

CREATE INDEX idx_dir_conn_enabled ON directory_connections (enabled);

-- ── User base DNs ─────────────────────────────────────────────────────────────
-- OUs / branches that contain user entries for this directory.
-- editable = TRUE means the application may create, move, and delete entries here.
CREATE TABLE directory_user_base_dns (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    directory_id  UUID          NOT NULL,
    dn            VARCHAR(1000) NOT NULL,
    display_order INTEGER       NOT NULL DEFAULT 0,
    editable      BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_dir_user_base_dns PRIMARY KEY (id),
    CONSTRAINT fk_user_base_dns_dir
        FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_base_dns_dir ON directory_user_base_dns (directory_id);

-- ── Group base DNs ────────────────────────────────────────────────────────────
-- OUs / branches that contain group entries for this directory.
CREATE TABLE directory_group_base_dns (
    id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    directory_id  UUID          NOT NULL,
    dn            VARCHAR(1000) NOT NULL,
    display_order INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT pk_dir_group_base_dns PRIMARY KEY (id),
    CONSTRAINT fk_group_base_dns_dir
        FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE CASCADE
);

CREATE INDEX idx_group_base_dns_dir ON directory_group_base_dns (directory_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- realms
-- A realm is a logical partition of a directory grouping a user OU, a group OU,
-- and the objectclass model used for user entries.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE realms (
    id                       UUID          NOT NULL DEFAULT gen_random_uuid(),
    directory_id             UUID          NOT NULL,
    name                     VARCHAR(255)  NOT NULL,
    user_base_dn             VARCHAR(1000) NOT NULL,
    group_base_dn            VARCHAR(1000) NOT NULL,
    primary_user_objectclass VARCHAR(255)  NOT NULL,
    display_order            INTEGER       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_realms PRIMARY KEY (id),
    CONSTRAINT fk_realms_directory
        FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE CASCADE
);

CREATE INDEX idx_realms_directory ON realms (directory_id);

-- ── Auxiliary objectClasses per realm ─────────────────────────────────────────
-- Additional objectClasses applied alongside the primary objectClass when
-- creating new user entries in this realm.
CREATE TABLE realm_auxiliary_objectclasses (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    realm_id         UUID         NOT NULL,
    objectclass_name VARCHAR(255) NOT NULL,
    display_order    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_realm_aux_objectclasses PRIMARY KEY (id),
    CONSTRAINT uq_realm_aux_objectclass   UNIQUE (realm_id, objectclass_name),
    CONSTRAINT fk_realm_aux_oc_realm
        FOREIGN KEY (realm_id) REFERENCES realms (id) ON DELETE CASCADE
);

-- ── realm_objectclasses ───────────────────────────────────────────────────────
-- Links a realm to an objectClass form (user_form) that drives the user
-- creation / edit UI for entries in this realm.
CREATE TABLE realm_objectclasses (
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    realm_id         UUID NOT NULL,
    -- Opaque reference to an objectClass (e.g. from LDAP schema discovery)
    object_class_id  UUID,
    -- FK to the user_form that provides the field configuration
    user_form_id     UUID,
    CONSTRAINT pk_realm_objectclasses PRIMARY KEY (id),
    CONSTRAINT fk_realm_objectclass_realm
        FOREIGN KEY (realm_id)      REFERENCES realms     (id) ON DELETE CASCADE,
    CONSTRAINT fk_realm_oc_user_form
        FOREIGN KEY (user_form_id)  REFERENCES user_form  (id) ON DELETE SET NULL
);

CREATE INDEX idx_realm_objectclasses_realm ON realm_objectclasses (realm_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Permission model
-- admin_realm_roles    — base role per account per realm (ADMIN | READ_ONLY)
-- admin_branch_restrictions — OU/branch DN scope restrictions per account/realm
-- admin_feature_permissions — per-feature enable/disable overrides
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE admin_realm_roles (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    admin_account_id UUID        NOT NULL,
    realm_id         UUID        NOT NULL,
    base_role        VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_realm_roles          PRIMARY KEY (id),
    CONSTRAINT uq_admin_realm_role           UNIQUE (admin_account_id, realm_id),
    CONSTRAINT chk_admin_realm_role_base_role CHECK (base_role IN ('ADMIN', 'READ_ONLY')),
    CONSTRAINT fk_arr_account
        FOREIGN KEY (admin_account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_arr_realm
        FOREIGN KEY (realm_id)         REFERENCES realms   (id) ON DELETE CASCADE
);

CREATE INDEX idx_arr_account ON admin_realm_roles (admin_account_id);
CREATE INDEX idx_arr_realm   ON admin_realm_roles (realm_id);

CREATE TABLE admin_branch_restrictions (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    admin_account_id UUID          NOT NULL,
    realm_id         UUID          NOT NULL,
    branch_dn        VARCHAR(1000) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_branch_restrictions PRIMARY KEY (id),
    CONSTRAINT uq_admin_realm_branch        UNIQUE (admin_account_id, realm_id, branch_dn),
    CONSTRAINT fk_abr_account
        FOREIGN KEY (admin_account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_abr_realm
        FOREIGN KEY (realm_id)         REFERENCES realms   (id) ON DELETE CASCADE
);

CREATE INDEX idx_abr_admin_realm ON admin_branch_restrictions (admin_account_id, realm_id);

CREATE TABLE admin_feature_permissions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    admin_account_id UUID         NOT NULL,
    feature_key      VARCHAR(100) NOT NULL,
    -- TRUE = feature enabled; FALSE = feature explicitly disabled
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_admin_feature_permissions PRIMARY KEY (id),
    CONSTRAINT uq_admin_feature             UNIQUE (admin_account_id, feature_key),
    CONSTRAINT fk_afp_account
        FOREIGN KEY (admin_account_id) REFERENCES accounts (id) ON DELETE CASCADE,
    CONSTRAINT chk_feature_key CHECK (feature_key IN (
        'user.create',
        'user.edit',
        'user.delete',
        'user.enable_disable',
        'user.move',
        'group.manage_members',
        'group.create_delete',
        'bulk.import',
        'bulk.export',
        'reports.run',
        'reports.export',
        'reports.schedule'
    ))
);

CREATE INDEX idx_afp_admin ON admin_feature_permissions (admin_account_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- csv_mapping_templates
-- Named CSV column-to-attribute mapping templates (§7.1, §10.2).
-- Scoped per directory; reusable across import jobs.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE csv_mapping_templates (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    directory_id         UUID         NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    -- LDAP attribute used to match CSV rows against existing directory entries
    target_key_attribute VARCHAR(255) NOT NULL DEFAULT 'uid',
    -- Default conflict resolution applied when a matching entry already exists
    conflict_handling    VARCHAR(20)  NOT NULL DEFAULT 'PROMPT',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_csv_mapping_templates  PRIMARY KEY (id),
    CONSTRAINT uq_csv_template_dir_name  UNIQUE (directory_id, name),
    CONSTRAINT fk_csv_template_directory
        FOREIGN KEY (directory_id) REFERENCES directory_connections (id) ON DELETE CASCADE,
    CONSTRAINT chk_conflict_handling
        CHECK (conflict_handling IN ('PROMPT', 'SKIP', 'OVERWRITE'))
);

CREATE INDEX idx_csv_templates_dir ON csv_mapping_templates (directory_id);

CREATE TABLE csv_mapping_template_entries (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    template_id      UUID         NOT NULL,
    -- Header name from the CSV file
    csv_column_name  VARCHAR(255) NOT NULL,
    -- 0-based column index; optional fallback when no header row is present
    csv_column_index INTEGER,
    -- NULL when ignored = TRUE; the column is present but discarded on import
    ldap_attribute   VARCHAR(255),
    ignored          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_csv_mapping_template_entries PRIMARY KEY (id),
    CONSTRAINT uq_csv_entry_template_col        UNIQUE (template_id, csv_column_name),
    CONSTRAINT fk_csv_entry_template
        FOREIGN KEY (template_id) REFERENCES csv_mapping_templates (id) ON DELETE CASCADE
);

CREATE INDEX idx_csv_entries_template ON csv_mapping_template_entries (template_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- scheduled_report_jobs
-- Scheduled report job definitions (§9.2).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE scheduled_report_jobs (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    directory_id        UUID          NOT NULL,
    name                VARCHAR(255)  NOT NULL,
    -- One of the seven standard report types defined in §9.1
    report_type         VARCHAR(50)   NOT NULL,
    -- Report-specific parameters as JSONB
    report_params       JSONB,
    -- Spring/Quartz cron expression (6 or 7 fields)
    cron_expression     VARCHAR(100)  NOT NULL,
    output_format       VARCHAR(10)   NOT NULL DEFAULT 'CSV',
    delivery_method     VARCHAR(10)   NOT NULL DEFAULT 'EMAIL',
    -- Comma-separated recipient email addresses (delivery_method = 'EMAIL')
    delivery_recipients TEXT,
    -- Object-key prefix for the S3 bucket (delivery_method = 'S3')
    s3_key_prefix       VARCHAR(500),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Populated after each execution attempt
    last_run_at         TIMESTAMPTZ,
    last_run_status     VARCHAR(50),
    last_run_message    TEXT,
    -- SET NULL if the creating account is later deleted
    created_by_admin_id UUID,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_scheduled_report_jobs PRIMARY KEY (id),
    CONSTRAINT fk_report_job_directory
        FOREIGN KEY (directory_id)        REFERENCES directory_connections (id) ON DELETE CASCADE,
    CONSTRAINT fk_report_job_created_by
        FOREIGN KEY (created_by_admin_id) REFERENCES accounts              (id) ON DELETE SET NULL,
    CONSTRAINT chk_report_type CHECK (report_type IN (
        'USERS_IN_GROUP',
        'USERS_IN_BRANCH',
        'USERS_WITH_NO_GROUP',
        'RECENTLY_ADDED',
        'RECENTLY_MODIFIED',
        'RECENTLY_DELETED',
        'DISABLED_ACCOUNTS'
    )),
    CONSTRAINT chk_report_output_format
        CHECK (output_format   IN ('CSV', 'PDF')),
    CONSTRAINT chk_report_delivery_method
        CHECK (delivery_method IN ('EMAIL', 'S3'))
);

CREATE INDEX idx_report_jobs_dir     ON scheduled_report_jobs (directory_id);
CREATE INDEX idx_report_jobs_enabled ON scheduled_report_jobs (enabled);

-- ─────────────────────────────────────────────────────────────────────────────
-- application_settings
-- Global application settings singleton (§10.2).
-- Covers branding, session timeout, SMTP, S3 storage, and admin auth config.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE application_settings (
    id                         UUID          NOT NULL DEFAULT gen_random_uuid(),

    -- ── Branding ──────────────────────────────────────────────────────────────
    app_name                   VARCHAR(255)  NOT NULL DEFAULT 'LDAP Portal',
    logo_url                   VARCHAR(1000),
    primary_colour             VARCHAR(20),
    secondary_colour           VARCHAR(20),

    -- ── Session ───────────────────────────────────────────────────────────────
    session_timeout_minutes    INTEGER       NOT NULL DEFAULT 60,

    -- ── SMTP mail relay ───────────────────────────────────────────────────────
    smtp_host                  VARCHAR(255),
    smtp_port                  INTEGER                DEFAULT 587,
    smtp_sender_address        VARCHAR(255),
    smtp_username              VARCHAR(255),
    smtp_password_encrypted    TEXT,
    smtp_use_tls               BOOLEAN       NOT NULL DEFAULT TRUE,

    -- ── S3-compatible object storage ──────────────────────────────────────────
    s3_endpoint_url            VARCHAR(500),
    s3_bucket_name             VARCHAR(255),
    s3_access_key              VARCHAR(255),
    s3_secret_key_encrypted    TEXT,
    s3_region                  VARCHAR(100),
    s3_presigned_url_ttl_hours INTEGER       NOT NULL DEFAULT 24,

    -- ── Admin authentication (global) ─────────────────────────────────────────
    -- LOCAL → bcrypt password in accounts.password_hash
    -- LDAP  → bind against the server described by ldap_auth_* columns
    admin_auth_type             VARCHAR(10)  NOT NULL DEFAULT 'LOCAL',
    ldap_auth_host              VARCHAR(255),
    ldap_auth_port              INTEGER,
    ldap_auth_ssl_mode          VARCHAR(10),
    ldap_auth_trust_all_certs   BOOLEAN      NOT NULL DEFAULT FALSE,
    ldap_auth_trusted_cert_pem  TEXT,
    -- Optional service-account credentials for initial bind / user lookup
    ldap_auth_bind_dn           VARCHAR(500),
    ldap_auth_bind_password_enc TEXT,
    -- Base DN under which user entries are searched
    ldap_auth_user_search_base  VARCHAR(500),
    -- Bind DN pattern; {username} is substituted at authentication time
    ldap_auth_bind_dn_pattern   VARCHAR(500),

    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_application_settings PRIMARY KEY (id),
    CONSTRAINT chk_admin_auth_type
        CHECK (admin_auth_type IN ('LOCAL', 'LDAP')),
    CONSTRAINT chk_ldap_auth_ssl_mode
        CHECK (ldap_auth_ssl_mode IS NULL OR ldap_auth_ssl_mode IN ('NONE', 'LDAPS', 'STARTTLS'))
);

-- ─────────────────────────────────────────────────────────────────────────────
-- audit_events
-- Immutable audit log for both internal API operations and LDAP changelog events.
-- actor_* and directory_* columns are denormalised to preserve history after
-- account or directory deletion.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE audit_events (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    -- Origin of the event
    source           VARCHAR(20)   NOT NULL,   -- 'INTERNAL' | 'LDAP_CHANGELOG'
    -- Who performed the action (NULL for LDAP_CHANGELOG events with no match)
    actor_id         UUID,
    actor_type       VARCHAR(20),              -- 'ADMIN' | 'SUPERADMIN'
    actor_username   VARCHAR(255),             -- denormalised for history
    -- Which directory was affected
    directory_id     UUID,
    directory_name   VARCHAR(255),             -- denormalised for history
    -- What happened
    action           VARCHAR(50)   NOT NULL,
    target_dn        VARCHAR(2000),
    -- Supplementary detail (attribute names, old values, etc.)
    detail           JSONB,
    -- Changelog-specific: the changeNumber / CSN from cn=changelog
    changelog_change_number VARCHAR(255),
    -- When the real operation happened vs when LDAPPortal recorded it
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    recorded_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT chk_audit_source
        CHECK (source     IN ('INTERNAL', 'LDAP_CHANGELOG')),
    CONSTRAINT chk_audit_actor_type
        CHECK (actor_type IS NULL OR actor_type IN ('ADMIN', 'SUPERADMIN'))
);

CREATE INDEX idx_audit_occurred   ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_directory  ON audit_events (directory_id, occurred_at DESC);
CREATE INDEX idx_audit_actor      ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_action     ON audit_events (action);
CREATE INDEX idx_audit_target_dn  ON audit_events (target_dn);
