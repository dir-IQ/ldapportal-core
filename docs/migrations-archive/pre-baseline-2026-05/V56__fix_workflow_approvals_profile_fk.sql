-- Fix profile deletion failing when workflow_approvals reference the profile.
-- The profile_id FK on workflow_approvals (formerly pending_approvals) was
-- created without an ON DELETE action, defaulting to RESTRICT. Approvals are
-- historical records that should be preserved, so SET NULL is appropriate.
ALTER TABLE workflow_approvals
    DROP CONSTRAINT pending_approvals_profile_id_fkey,
    ADD CONSTRAINT fk_wa_profile
        FOREIGN KEY (profile_id) REFERENCES provisioning_profiles(id) ON DELETE SET NULL;
