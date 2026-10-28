-- Hold for review replaces the delete policy. DELETES holds the target delete
-- of an entry that leaves the set (the former REVIEW policy), NONE applies it
-- (the former DELETE policy), and ALL also holds adds, updates and moves.

ALTER TABLE sync_set
    ADD COLUMN hold_for_review VARCHAR(16) NOT NULL DEFAULT 'DELETES';

UPDATE sync_set SET hold_for_review = 'NONE' WHERE delete_policy = 'DELETE';

ALTER TABLE sync_set
    ADD CONSTRAINT sync_set_hold_for_review_check CHECK (hold_for_review IN ('NONE', 'DELETES', 'ALL'));

ALTER TABLE sync_set DROP CONSTRAINT sync_set_delete_policy_check;

ALTER TABLE sync_set DROP COLUMN delete_policy;
