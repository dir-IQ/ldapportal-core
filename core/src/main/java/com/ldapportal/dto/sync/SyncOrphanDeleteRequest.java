// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Orphaned drift items to delete from the target, by their position in the
 * stored verify. {@code verifiedAt} is the time of the verify the caller chose
 * them from (see {@link SyncDriftRepairRequest}). {@code confirm} must be
 * exactly {@code DELETE} to delete (the dialog's typed confirmation); the
 * preview ignores it.
 */
public record SyncOrphanDeleteRequest(@NotEmpty @Size(max = 200) List<Integer> seqs,
                                      @NotNull OffsetDateTime verifiedAt, String confirm) {

    /** The word an operator types to confirm a delete. */
    public static final String CONFIRM_WORD = "DELETE";
}
