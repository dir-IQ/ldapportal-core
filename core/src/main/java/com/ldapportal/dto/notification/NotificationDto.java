// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.notification;

import com.ldapportal.entity.Notification;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationDto(
        UUID id,
        String type,
        String title,
        String body,
        String link,
        UUID directoryId,
        boolean read,
        OffsetDateTime createdAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(
                n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getLink(), n.getDirectoryId(), n.isRead(), n.getCreatedAt());
    }
}
