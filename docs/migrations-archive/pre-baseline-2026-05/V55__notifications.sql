-- In-app notification center.
-- One notification per account per event, with deep link for navigation.

CREATE TABLE notifications (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    account_id      UUID          NOT NULL,
    directory_id    UUID,
    type            VARCHAR(50)   NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    body            TEXT,
    link            VARCHAR(500),
    read            BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_account FOREIGN KEY (account_id)
        REFERENCES accounts (id) ON DELETE CASCADE
);

-- Primary query: unread notifications for a user, newest first
CREATE INDEX idx_notifications_account_unread
    ON notifications (account_id, created_at DESC) WHERE NOT read;

-- All notifications for a user (paginated)
CREATE INDEX idx_notifications_account_all
    ON notifications (account_id, created_at DESC);
