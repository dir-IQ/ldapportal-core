// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.ldapportal.entity.enums.SslMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Create / update request for a directory connection.
 *
 * <p>{@code bindPassword} is the plaintext password — the service layer
 * encrypts it before persisting.  On update, pass {@code null} to keep
 * the existing encrypted value.</p>
 *
 * <p>{@code slug} is the optional stable IaC external key. On {@code POST}
 * create it is auto-derived from {@code displayName} when absent; on the
 * {@code by-slug} upsert it comes from the path. It is immutable, so any
 * value sent on a plain {@code PUT /{id}} update is ignored.</p>
 */
public record DirectoryConnectionRequest(
        DirectoryType directoryType,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 255) String host,          // required for LDAP, unused for Entra
        @Min(1) @Max(65535) int port,
        SslMode sslMode,
        boolean trustAllCerts,
        String trustedCertificatePem,
        String bindDn,                          // required for LDAP, unused for Entra
        String bindPassword,
        String baseDn,                          // required for LDAP, unused for Entra
        @Min(0) @Max(5000) int pagingSize,
        @Min(0) int poolMinSize,
        @Min(0) int poolMaxSize,
        @Min(0) int poolConnectTimeoutSeconds,
        @Min(0) int poolResponseTimeoutSeconds,
        String enableDisableAttribute,
        EnableDisableValueType enableDisableValueType,
        String enableValue,
        String disableValue,
        UUID auditDataSourceId,
        boolean enabled,
        boolean selfServiceEnabled,
        String selfServiceLoginAttribute,
        String secondaryHost,
        @Min(1) @Max(65535) Integer secondaryPort,
        @Min(1) @Max(65535) Integer globalCatalogPort,
        @Valid List<BaseDnRequest> userBaseDns,
        @Valid List<BaseDnRequest> groupBaseDns,
        // ── Entry classification object classes (V20) ────────────────────────
        // Null/empty = use the vendor default for directoryType. The service
        // pre-populates these from the vendor default on create when omitted.
        List<String> userObjectClasses,
        List<String> groupObjectClasses,
        // ── Entra ID fields ─────────────────────────────────────────────────
        String tenantId,
        String entraClientId,
        String entraClientSecret,               // plaintext; null on update = keep existing
        String graphEndpoint,
        // ── IaC external key (optional; auto-derived from displayName when absent) ──
        @Size(max = 100)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "slug must be lowercase alphanumeric segments separated by single hyphens")
        String slug) {
}
