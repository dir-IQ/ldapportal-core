// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The drift items to repair (or preview repairing), by their position in the stored verify. */
public record SyncDriftRepairRequest(@NotEmpty @Size(max = 200) List<Integer> seqs) {
}
