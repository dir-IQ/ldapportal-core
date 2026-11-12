// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
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
 * @param beneath           the entries beneath it on the target: for a check, the ones found
 *                          (so an operator can choose to delete them too); for a delete that
 *                          included them, the ones deleted (each audited). Empty otherwise.
 */
public record SyncDeleteApproval(
        SyncPreviewAction outcome,
        UUID targetDirectoryId,
        String targetDn,
        String message,
        @JsonIgnore boolean alreadyGone,
        List<String> beneath) {

    public SyncDeleteApproval {
        beneath = beneath == null ? List.of() : List.copyOf(beneath);
    }

    /** A result with nothing beneath the entry to report. */
    public SyncDeleteApproval(SyncPreviewAction outcome, UUID targetDirectoryId, String targetDn, String message,
                              boolean alreadyGone) {
        this(outcome, targetDirectoryId, targetDn, message, alreadyGone, List.of());
    }

    /** A result for an entry that was there to act on. */
    public SyncDeleteApproval(SyncPreviewAction outcome, UUID targetDirectoryId, String targetDn, String message) {
        this(outcome, targetDirectoryId, targetDn, message, false, List.of());
    }
}
