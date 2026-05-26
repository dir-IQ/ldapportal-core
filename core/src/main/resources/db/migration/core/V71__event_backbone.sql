-- V71: Event backbone (Prerequisite B from enterprise-roadmap.md).
-- Durable outbox + subscription registry for outbound events delivered
-- via pluggable channels (v1: WebhookChannel).
-- See docs/superpowers/specs/2026-04-23-event-backbone-design.md.

CREATE TABLE event_subscription (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),

    -- Transport enum; v1: 'WEBHOOK'. Stored as string to avoid Postgres enum DDL.
    channel_type        VARCHAR(32)  NOT NULL,

    -- Transport-specific config as JSONB. For WEBHOOK:
    --   {"url":"https://...","auth":{"type":"bearer","tokenEnc":"<ciphertext>"}}
    --   {"url":"https://...","auth":{"type":"hmac","secretEnc":"<ciphertext>"}}
    --   {"url":"https://...","auth":null}
    -- Secret fields end in 'Enc' and carry EncryptionService output.
    destination_config  JSONB        NOT NULL,

    -- Null = receive every event type. Non-null = JSON array of wire-names.
    -- e.g. ["api_token.created","directory.created"]
    event_type_filter   JSONB,

    enabled             BOOLEAN      NOT NULL DEFAULT true,

    created_by_id       UUID         REFERENCES accounts(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Optimistic lock; matches ApiToken's pattern.
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_event_subscription_name_not_blank
        CHECK (length(trim(name)) > 0)
);

CREATE INDEX idx_event_subscription_enabled
    ON event_subscription (enabled)
    WHERE enabled = true;


CREATE TABLE event_outbox (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    subscription_id     UUID         NOT NULL
                                     REFERENCES event_subscription(id) ON DELETE CASCADE,

    -- Consumer-side dedup; matches X-LDAPPortal-Event-Id header.
    event_id            UUID         NOT NULL,

    event_type          VARCHAR(100) NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,

    -- Full envelope JSON (exactly what gets POSTed as the body).
    envelope            JSONB        NOT NULL,

    -- Lifecycle: PENDING -> DELIVERING -> {DELIVERED, PENDING+backoff, DEAD_LETTERED}.
    status              VARCHAR(20)  NOT NULL,

    attempts            INT          NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ  NOT NULL,
    last_error          TEXT,
    last_http_status    INT,

    delivered_at        TIMESTAMPTZ,
    dead_lettered_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_event_outbox_status
        CHECK (status IN ('PENDING','DELIVERING','DELIVERED','DEAD_LETTERED'))
);

-- Hot dispatcher query: PENDING rows ready to dispatch.
CREATE INDEX idx_event_outbox_dispatch
    ON event_outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- Stale-sweeper query: DELIVERING rows stuck past the crash-recovery cutoff.
CREATE INDEX idx_event_outbox_delivering
    ON event_outbox (next_attempt_at)
    WHERE status = 'DELIVERING';

-- Dead-letter list + per-subscription history.
CREATE INDEX idx_event_outbox_subscription_status
    ON event_outbox (subscription_id, status, created_at DESC);
