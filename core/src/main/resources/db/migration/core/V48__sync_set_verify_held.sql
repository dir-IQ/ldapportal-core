-- SPDX-License-Identifier: Apache-2.0
--
-- From a sync set's last complete content verify: how many members have a
-- reference value the sync holds back (it names an entry the sync hasn't
-- placed), and how many of those name an entry that doesn't exist on the
-- source at all. Not drift: shown alongside it. Null until a verify counts them.
ALTER TABLE sync_set
    ADD COLUMN verify_held_count     INTEGER,
    ADD COLUMN verify_dangling_count INTEGER;
