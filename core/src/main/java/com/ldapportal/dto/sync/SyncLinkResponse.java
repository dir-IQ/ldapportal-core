// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.enums.SyncCaptureMode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response view of a {@link SyncLink}. */
public record SyncLinkResponse(
        UUID id,
        String displayName,
        UUID sourceDirId,
        UUID targetDirId,
        boolean enabled,
        SyncCaptureMode captureMode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version) {

    public static SyncLinkResponse of(SyncLink l) {
        return new SyncLinkResponse(l.getId(), l.getDisplayName(), l.getSourceDirId(), l.getTargetDirId(),
                l.isEnabled(), l.getCaptureMode(), l.getCreatedAt(), l.getUpdatedAt(), l.getVersion());
    }
}
