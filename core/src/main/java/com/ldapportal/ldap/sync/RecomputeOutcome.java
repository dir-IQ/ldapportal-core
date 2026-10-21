// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;

/**
 * What one live recompute did to an entry.
 *
 * @param action   the outcome, in the preview's vocabulary ({@code ERROR} for a failed write)
 * @param targetDn the target entry it concerned, when known
 * @param reason   why it was held for review or failed, else {@code null}
 */
public record RecomputeOutcome(SyncPreviewAction action, String targetDn, String reason) {
}
