// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The drift items to repair (or preview repairing), by their position in the
 * stored verify. {@code verifiedAt} is the time of the verify the caller chose
 * them from. Positions are renumbered by every complete verify, so when a
 * newer one has replaced it, each item is found in the newer one by its
 * {@code targetDns} entry (the stored target DN, same order as {@code seqs});
 * without them such a request is refused rather than acting on other entries.
 */
public record SyncDriftRepairRequest(@NotEmpty @Size(max = 200) List<Integer> seqs,
                                     @NotNull OffsetDateTime verifiedAt,
                                     @Size(max = 200) List<String> targetDns) {

    /** Without target DNs: refused if a newer verify has replaced the one at {@code verifiedAt}. */
    public SyncDriftRepairRequest(List<Integer> seqs, OffsetDateTime verifiedAt) {
        this(seqs, verifiedAt, null);
    }
}
