-- SPDX-License-Identifier: Apache-2.0
-- Expression indexes backing the entry-timeline query (audit events filtered by
-- a target DN). Two classes of user-relevant rows are NOT keyed by the user's
-- own DN:
--   * group add/remove rows are keyed by the *group* DN, with the affected user
--     in detail->>'member';
--   * a user-move row is keyed by the old DN and carries the post-move DN in
--     detail->>'newDn'.
-- AuditEventRepository now matches a targetDn against
--   target_dn OR detail->>'member' OR detail->>'newDn'
-- so a user's history shows their group-membership changes and the move into
-- their new location. These expression indexes let Postgres BitmapOr the JSON
-- branches together with the existing idx_audit_target_dn instead of falling
-- back to a sequential scan.
CREATE INDEX IF NOT EXISTS idx_audit_detail_member
    ON audit_events ((detail ->> 'member'));

CREATE INDEX IF NOT EXISTS idx_audit_detail_new_dn
    ON audit_events ((detail ->> 'newDn'));
