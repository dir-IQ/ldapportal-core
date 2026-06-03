// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Pre-save changelog test (§9): probe a source directory's changelog before a
 * link exists. {@code changelogBaseDn} defaults to {@code cn=changelog}.
 */
public record TestChangelogRequest(
        @NotNull UUID sourceDirectoryId,
        @Size(max = 500) String changelogBaseDn) {
}
