// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;
import java.util.Map;

/**
 * Full detail for one preview row, fetched lazily when the operator opens it.
 * For content/add records {@code attributes} holds the entry's attributes; for
 * a modify it holds the modified attributes' target values. Multi-valued
 * attributes are capped (see {@code LdifPreviewService} value cap) so a group
 * with thousands of members can't blow up the payload.
 *
 * @param rowNumber   1-based position in the uploaded LDIF
 * @param dn          target DN
 * @param op          classified operation
 * @param attributes  attribute name → (capped) values
 * @param memberDelta net member change for a group modify (null otherwise)
 * @param issues      per-row findings
 */
public record LdifPreviewRowDetail(
        int rowNumber,
        String dn,
        LdifPreviewOp op,
        Map<String, List<String>> attributes,
        LdifMemberDelta memberDelta,
        List<LdifPreviewIssue> issues) {
}
