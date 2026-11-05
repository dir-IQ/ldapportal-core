-- SPDX-License-Identifier: Apache-2.0
--
-- Every drift item a sync set's last complete content verify found: a member
-- missing on the target, a target entry the set no longer accounts for
-- (orphan), or an entry whose attributes differ from the projected source
-- (mismatch, with the differing attributes as JSON). Only the latest verify is
-- kept: a complete verify replaces the set's rows, alongside the counts it
-- records on sync_set. Rows go with their set.
CREATE TABLE sync_verify_entry (
    sync_set_id UUID        NOT NULL REFERENCES sync_set(id) ON DELETE CASCADE,
    seq         INTEGER     NOT NULL,
    category    VARCHAR(16) NOT NULL,
    target_dn   TEXT        NOT NULL,
    source_dn   TEXT,
    identity    TEXT,
    attributes  TEXT,
    PRIMARY KEY (sync_set_id, seq)
);

CREATE INDEX idx_sync_verify_entry_category ON sync_verify_entry (sync_set_id, category, seq);
