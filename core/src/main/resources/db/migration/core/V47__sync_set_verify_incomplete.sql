-- SPDX-License-Identifier: Apache-2.0
--
-- When a sync set's content verify last ran without reading both directories
-- completely. Its counts aren't stored (they'd be partial), so the stored drift
-- snapshot stays at the last complete verify; this records that a newer check
-- didn't finish, so the UI can say the snapshot may be out of date. Cleared by
-- the next complete verify.
ALTER TABLE sync_set ADD COLUMN verify_incomplete_at TIMESTAMPTZ;
