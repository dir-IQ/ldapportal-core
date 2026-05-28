-- SIEM / syslog export configuration columns on application_settings
ALTER TABLE application_settings
    ADD COLUMN siem_enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN siem_protocol         VARCHAR(20),          -- SYSLOG_UDP, SYSLOG_TCP, WEBHOOK
    ADD COLUMN siem_host             VARCHAR(500),
    ADD COLUMN siem_port             INTEGER,
    ADD COLUMN siem_format           VARCHAR(20),          -- RFC5424, CEF, JSON
    ADD COLUMN siem_auth_token_enc   TEXT,                 -- AES-256 encrypted bearer token
    ADD COLUMN webhook_url           VARCHAR(2000),
    ADD COLUMN webhook_auth_header_enc TEXT;               -- AES-256 encrypted Authorization header
