-- SPDX-License-Identifier: Apache-2.0
--
-- Every held reference a sync set's last complete content verify found: one
-- row per reference value the sync holds back because the entry it names isn't
-- placed by any sync set (a group's member, a user's manager, an IVIA secDN).
-- Not drift: the target keeps its own value. `status` says whether the named
-- entry doesn't exist on the source (DANGLING: a source data problem), exists
-- but isn't synced yet (UNSYNCED: it fills in once that entry is), or wasn't
-- looked up (UNCHECKED). Only the latest verify is kept, as with
-- sync_verify_entry; rows go with their set.
CREATE TABLE sync_verify_held (
    sync_set_id UUID         NOT NULL REFERENCES sync_set(id) ON DELETE CASCADE,
    seq         INTEGER      NOT NULL,
    entry_dn    TEXT         NOT NULL,
    source_dn   TEXT,
    identity    TEXT,
    attribute   VARCHAR(256) NOT NULL,
    reference   TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    PRIMARY KEY (sync_set_id, seq)
);

CREATE INDEX idx_sync_verify_held_status ON sync_verify_held (sync_set_id, status, seq);
