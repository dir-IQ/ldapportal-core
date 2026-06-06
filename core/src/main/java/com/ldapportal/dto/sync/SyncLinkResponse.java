// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response view of a {@link SyncLink}, including changelog-capture status. */
public record SyncLinkResponse(
        UUID id,
        String displayName,
        UUID sourceDirId,
        UUID targetDirId,
        boolean enabled,
        SyncCaptureMode captureMode,
        ChangelogFormat changelogFormat,
        String changelogBaseDn,
        SyncChangelogHealth changelogHealth,
        Long changelogLastChangeNumber,
        Long changelogSourceLastChangeNumber,
        OffsetDateTime changelogLastPolledAt,
        String changelogLastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version) {

    public static SyncLinkResponse of(SyncLink l) {
        return new SyncLinkResponse(l.getId(), l.getDisplayName(), l.getSourceDirId(), l.getTargetDirId(),
                l.isEnabled(), l.getCaptureMode(), l.getChangelogFormat(), l.getChangelogBaseDn(),
                l.getChangelogHealth(), l.getChangelogLastChangeNumber(), l.getChangelogSourceLastChangeNumber(),
                l.getChangelogLastPolledAt(), l.getChangelogLastError(),
                l.getCreatedAt(), l.getUpdatedAt(), l.getVersion());
    }
}
