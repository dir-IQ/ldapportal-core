// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Directory-agnostic audit event representation.
 */
public record DirectoryAuditEvent(
        String id,
        String actorName,
        String action,
        String targetName,
        String targetId,
        OffsetDateTime occurredAt,
        Map<String, Object> detail
) {}
