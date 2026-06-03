// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body for the changelog rewind remediation endpoint: the {@code changeNumber}
 * to set the link's cursor back to. The next poll resumes from
 * {@code changeNumber + 1}.
 */
public record RewindChangelogRequest(
        @NotNull @PositiveOrZero Long changeNumber) {
}
