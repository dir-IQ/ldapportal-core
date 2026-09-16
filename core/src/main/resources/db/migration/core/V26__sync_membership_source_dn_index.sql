-- SPDX-License-Identifier: Apache-2.0
-- DN-reference remapping now resolves a referenced source DN across every sync
-- link (e.g. an IVIA secDN under c=admin pointing at an entry mirrored by the
-- c=us link), so the membership index is looked up by source_dn alone. The
-- existing (sync_set_id, source_dn) index cannot serve that lookup.
CREATE INDEX idx_sync_membership_source_dn ON sync_membership (source_dn);
