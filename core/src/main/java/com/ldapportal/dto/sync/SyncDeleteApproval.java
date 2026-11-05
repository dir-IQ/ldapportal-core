// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * Result of an operator approving a held delete.
 *
 * @param outcome           {@code DELETE} (target entry removed, row dropped), {@code FORGET}
 *                          (another identity owns the target entry: it was kept and only this
 *                          row was dropped), or {@code ERROR} (the delete failed; the row is
 *                          now FAILED with the reason)
 * @param targetDirectoryId the target directory
 * @param targetDn          the target entry
 * @param message           the failure reason, for {@code ERROR}
 * @param alreadyGone       the target entry was already absent, so there was nothing to
 *                          delete (an orphan check's {@code UNCHANGED}); not sent to clients
 */
public record SyncDeleteApproval(
        SyncPreviewAction outcome,
        UUID targetDirectoryId,
        String targetDn,
        String message,
        @JsonIgnore boolean alreadyGone) {

    /** A result for an entry that was there to act on. */
    public SyncDeleteApproval(SyncPreviewAction outcome, UUID targetDirectoryId, String targetDn, String message) {
        this(outcome, targetDirectoryId, targetDn, message, false);
    }
}
