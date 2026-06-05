// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Lightweight per-record row for the preview table — no attribute values, so
 * a 2–5K preview pages cheaply. Full values come from
 * {@link LdifPreviewRowDetail} on demand.
 *
 * @param rowNumber     1-based position in the uploaded LDIF
 * @param dn            target DN (null only for unparseable records)
 * @param op            classified operation
 * @param objectClasses objectClass values for add/content records (else empty)
 * @param attrCount     attribute count (content add) or modification count (modify)
 * @param memberDelta   net member change for a group modify (null otherwise)
 * @param memberCount   member count for a group add (null otherwise)
 * @param issues        per-row findings
 * @param userAdd       true when this row is a <em>new</em> user-entry add that
 *                      will be routed through the provisioning SPI (so a vendor
 *                      interceptor, e.g. ISVA, may augment it). False for
 *                      non-user entries, updates/skips, change records, and
 *                      entries that already carry the vendor overlay.
 */
public record LdifPreviewRow(
        int rowNumber,
        String dn,
        LdifPreviewOp op,
        List<String> objectClasses,
        int attrCount,
        LdifMemberDelta memberDelta,
        Integer memberCount,
        List<LdifPreviewIssue> issues,
        boolean userAdd) {
}
