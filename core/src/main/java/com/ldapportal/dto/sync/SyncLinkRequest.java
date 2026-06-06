// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SyncCaptureMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create/update payload for a {@link com.ldapportal.entity.SyncLink} — one
 * directional source→target sync. For {@code CHANGELOG} capture the changelog
 * format + base DN are required (validated in the service).
 */
public record SyncLinkRequest(
        @NotBlank @Size(max = 255) String displayName,
        @NotNull UUID sourceDirId,
        @NotNull UUID targetDirId,
        boolean enabled,
        SyncCaptureMode captureMode,
        ChangelogFormat changelogFormat,
        @Size(max = 500) String changelogBaseDn) {
}
