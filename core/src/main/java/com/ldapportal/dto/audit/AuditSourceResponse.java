// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.audit;

import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SslMode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditSourceResponse(
        UUID id,
        String displayName,
        String host,
        int port,
        SslMode sslMode,
        boolean trustAllCerts,
        String bindDn,
        String changelogBaseDn,
        String branchFilterDn,
        ChangelogFormat changelogFormat,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AuditSourceResponse from(AuditDataSource src) {
        return new AuditSourceResponse(
                src.getId(),
                src.getDisplayName(),
                src.getHost(),
                src.getPort(),
                src.getSslMode(),
                src.isTrustAllCerts(),
                src.getBindDn(),
                src.getChangelogBaseDn(),
                src.getBranchFilterDn(),
                src.getChangelogFormat(),
                src.isEnabled(),
                src.getCreatedAt(),
                src.getUpdatedAt()
        );
    }
}
