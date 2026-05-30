-- SPDX-License-Identifier: Apache-2.0
--
-- P0 of the directory-sync feature: persistence for async fan-out of
-- app-initiated LDAP writes from a source directory to a target
-- directory. See docs/plans/2026-05-30-directory-sync-design.md.
--
-- Three tables:
--   replication_links             — source → target configuration
--   replication_link_attr_mappings — optional per-link attribute rename
--                                    + value-template rules
--   replication_events            — durable queue of pending / in-flight
--                                    / delivered / failed events

CREATE TABLE replication_links (
    id                       UUID PRIMARY KEY,
    display_name             VARCHAR(255) NOT NULL,
    source_dir_id            UUID NOT NULL REFERENCES directory_connections(id),
    target_dir_id            UUID NOT NULL REFERENCES directory_connections(id),
    -- NULL pair = identity DN mapping; both NULL or both set together.
    source_base_dn           VARCHAR(500),
    target_base_dn           VARCHAR(500),
    enabled                  BOOLEAN NOT NULL DEFAULT true,
    auto_create_on_missing   BOOLEAN NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT replication_links_distinct_endpoints
        CHECK (source_dir_id <> target_dir_id),
    CONSTRAINT replication_links_dn_pair_consistency
        CHECK ((source_base_dn IS NULL) = (target_base_dn IS NULL))
);

CREATE INDEX idx_replication_links_source_dir
    ON replication_links(source_dir_id) WHERE enabled;

COMMENT ON TABLE replication_links IS
    'Source → target replication topology, one row per pair. enabled=false '
    'pauses the link without losing event history.';
COMMENT ON COLUMN replication_links.auto_create_on_missing IS
    'When MODIFY hits a target that does not have the entry, optionally '
    'backfill via source SUB read + ADD before retrying the MODIFY.';

CREATE TABLE replication_link_attr_mappings (
    link_id        UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    source_attr    VARCHAR(255) NOT NULL,
    target_attr    VARCHAR(255) NOT NULL,
    -- Identity when NULL. Supports a single dollar-brace value substitution
    -- token in v1 — no scripting. See AttributeMapper.VALUE_TOKEN. Examples:
    --   "<TOKEN>@corp.com"   prepends a literal suffix
    --   "<TOKEN>"            equivalent to NULL (explicit identity)
    value_template VARCHAR(2000),
    PRIMARY KEY (link_id, source_attr)
);

COMMENT ON TABLE replication_link_attr_mappings IS
    'Optional per-link attribute rename and value-template rules. Empty '
    'for a link means identity mapping (no rename, no value transform).';

CREATE TABLE replication_events (
    id              UUID PRIMARY KEY,
    link_id         UUID NOT NULL REFERENCES replication_links(id) ON DELETE CASCADE,
    -- APP_INTERCEPT (v1, this branch) | SOURCE_CHANGELOG (future). Lets a
    -- future changelog-based capture path land additively without
    -- migration; the worker is source-agnostic.
    enqueue_source  VARCHAR(20) NOT NULL DEFAULT 'APP_INTERCEPT',
    operation       VARCHAR(20) NOT NULL,
    source_dn       VARCHAR(2000) NOT NULL,
    target_dn       VARCHAR(2000) NOT NULL,
    -- Mapped payload, ready to apply against the target. Shape depends on
    -- operation; see ReplicationOperation javadoc.
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT replication_events_status_check
        CHECK (status IN ('PENDING','IN_FLIGHT','DELIVERED','FAILED','DEAD_LETTERED','SKIPPED','ACKNOWLEDGED')),
    CONSTRAINT replication_events_operation_check
        CHECK (operation IN ('ADD','MODIFY','DELETE','MODIFY_DN')),
    CONSTRAINT replication_events_enqueue_source_check
        CHECK (enqueue_source IN ('APP_INTERCEPT','SOURCE_CHANGELOG'))
);

-- Worker-drainer index: pick up PENDING / FAILED events in enqueue order,
-- skipping events whose next_attempt_at is in the future. Partial index
-- keeps it small as DELIVERED rows accumulate.
CREATE INDEX idx_replication_events_pending
    ON replication_events(link_id, enqueued_at)
    WHERE status IN ('PENDING','FAILED');

-- UI surfacing: per-link counts by status, plus filterable detail view.
CREATE INDEX idx_replication_events_link_status
    ON replication_events(link_id, status);

COMMENT ON TABLE replication_events IS
    'Durable queue of source → target replication events. Worker drains '
    'PENDING/FAILED in per-link FIFO order with exponential backoff; '
    'DEAD_LETTERED events stay visible until the operator acknowledges.';
