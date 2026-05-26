// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryGroupBaseDn;
import com.ldapportal.entity.DirectoryUserBaseDn;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.ldapportal.entity.enums.SslMode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Directory connection response — bind password is never included. */
public record DirectoryConnectionResponse(
        UUID id,
        DirectoryType directoryType,
        String displayName,
        String host,
        int port,
        SslMode sslMode,
        boolean trustAllCerts,
        String bindDn,
        String baseDn,
        int pagingSize,
        int poolMinSize,
        int poolMaxSize,
        int poolConnectTimeoutSeconds,
        int poolResponseTimeoutSeconds,
        String enableDisableAttribute,
        EnableDisableValueType enableDisableValueType,
        String enableValue,
        String disableValue,
        UUID auditDataSourceId,
        boolean enabled,
        boolean selfServiceEnabled,
        String selfServiceLoginAttribute,
        List<BaseDnItem> userBaseDns,
        List<BaseDnItem> groupBaseDns,
        String secondaryHost,
        Integer secondaryPort,
        Integer globalCatalogPort,
        // ── Entra ID fields ─────────────────────────────────────────────────
        String tenantId,
        String entraClientId,
        String graphEndpoint,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record BaseDnItem(UUID id, String dn, int displayOrder) {
        public static BaseDnItem fromUser(DirectoryUserBaseDn b) {
            return new BaseDnItem(b.getId(), b.getDn(), b.getDisplayOrder());
        }

        public static BaseDnItem fromGroup(DirectoryGroupBaseDn b) {
            return new BaseDnItem(b.getId(), b.getDn(), b.getDisplayOrder());
        }
    }

    public static DirectoryConnectionResponse from(DirectoryConnection dc,
                                                   List<DirectoryUserBaseDn> userDns,
                                                   List<DirectoryGroupBaseDn> groupDns) {
        return new DirectoryConnectionResponse(
                dc.getId(),
                dc.getDirectoryType(),
                dc.getDisplayName(),
                dc.getHost(),
                dc.getPort(),
                dc.getSslMode(),
                dc.isTrustAllCerts(),
                dc.getBindDn(),
                dc.getBaseDn(),
                dc.getPagingSize(),
                dc.getPoolMinSize(),
                dc.getPoolMaxSize(),
                dc.getPoolConnectTimeoutSeconds(),
                dc.getPoolResponseTimeoutSeconds(),
                dc.getEnableDisableAttribute(),
                dc.getEnableDisableValueType(),
                dc.getEnableValue(),
                dc.getDisableValue(),
                dc.getAuditDataSource() != null ? dc.getAuditDataSource().getId() : null,
                dc.isEnabled(),
                dc.isSelfServiceEnabled(),
                dc.getSelfServiceLoginAttribute(),
                userDns.stream().map(BaseDnItem::fromUser).toList(),
                groupDns.stream().map(BaseDnItem::fromGroup).toList(),
                dc.getSecondaryHost(),
                dc.getSecondaryPort(),
                dc.getGlobalCatalogPort(),
                dc.getTenantId(),
                dc.getEntraClientId(),
                dc.getGraphEndpoint(),
                dc.getCreatedAt(),
                dc.getUpdatedAt());
    }
}
