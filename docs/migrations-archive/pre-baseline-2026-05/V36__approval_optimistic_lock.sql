-- Add optimistic locking version column to workflow_approvals
-- to prevent concurrent double-execution of approve() (finding H3).
ALTER TABLE workflow_approvals ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
