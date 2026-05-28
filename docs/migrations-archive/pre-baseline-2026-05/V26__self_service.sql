-- Self-service portal and self-registration support

-- ── Directory self-service settings ──────────────────────────────────────────
ALTER TABLE directory_connections
  ADD COLUMN self_service_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN self_service_login_attribute  VARCHAR(64) DEFAULT 'uid';

-- ── Registration requests ────────────────────────────────────────────────────
CREATE TABLE registration_requests (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  directory_id          UUID NOT NULL REFERENCES directory_connections(id) ON DELETE CASCADE,
  profile_id            UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
  attributes            JSONB NOT NULL,
  email                 VARCHAR(255) NOT NULL,
  status                VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
  email_verified        BOOLEAN NOT NULL DEFAULT FALSE,
  verification_token    VARCHAR(255),
  verification_expires  TIMESTAMP WITH TIME ZONE,
  justification         TEXT,
  pending_approval_id   UUID REFERENCES pending_approvals(id),
  created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_registration_requests_directory ON registration_requests(directory_id);
CREATE INDEX idx_registration_requests_profile   ON registration_requests(profile_id);
CREATE INDEX idx_registration_requests_status    ON registration_requests(status);
CREATE INDEX idx_registration_requests_token     ON registration_requests(verification_token);
