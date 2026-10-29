// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.UUID;

/**
 * Result of an operator approving an add, update or move held for review.
 *
 * @param outcome           what the approval did, in the preview's vocabulary: {@code ADD},
 *                          {@code MODIFY} or {@code MOVE} when it wrote; {@code NO_CHANGE} when
 *                          the target already matched the source; {@code REVIEW} when the entry is
 *                          held again (it has left the set and its delete is held, or brownfield
 *                          matching needs a decision); {@code ERROR} when the write failed (the row
 *                          is now FAILED with the reason)
 * @param targetDirectoryId the target directory
 * @param targetDn          the target entry
 * @param message           why it was held again or failed, else {@code null}
 */
public record SyncUpdateApproval(
        SyncPreviewAction outcome,
        UUID targetDirectoryId,
        String targetDn,
        String message) {
}
