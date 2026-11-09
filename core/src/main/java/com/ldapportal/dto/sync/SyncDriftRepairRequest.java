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
 * them from: positions are renumbered by every complete verify, so a request
 * made against an older one is refused rather than acting on other entries.
 */
public record SyncDriftRepairRequest(@NotEmpty @Size(max = 200) List<Integer> seqs,
                                     @NotNull OffsetDateTime verifiedAt) {
}
