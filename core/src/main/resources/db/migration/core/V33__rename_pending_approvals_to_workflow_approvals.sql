-- Rename pending_approvals table to workflow_approvals
ALTER TABLE pending_approvals RENAME TO workflow_approvals;

-- Rename constraints
ALTER TABLE workflow_approvals RENAME CONSTRAINT pk_pending_approvals TO pk_workflow_approvals;
ALTER TABLE workflow_approvals RENAME CONSTRAINT fk_pa_directory TO fk_wa_directory;
ALTER TABLE workflow_approvals RENAME CONSTRAINT fk_pa_requester TO fk_wa_requester;
ALTER TABLE workflow_approvals RENAME CONSTRAINT fk_pa_reviewer TO fk_wa_reviewer;
ALTER TABLE workflow_approvals RENAME CONSTRAINT chk_pa_status TO chk_wa_status;
