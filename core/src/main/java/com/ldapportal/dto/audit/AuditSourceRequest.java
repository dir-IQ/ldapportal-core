// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.audit;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create / update request for an {@link com.ldapportal.entity.AuditDataSource}.
 *
 * <p>{@code bindPassword} is plaintext — the service encrypts before persisting.
 * Pass {@code null} on update to keep the existing encrypted value.</p>
 */
public record AuditSourceRequest(
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(max = 255) String host,
        @Min(1) @Max(65535) int port,
        @NotNull SslMode sslMode,
        boolean trustAllCerts,
        String trustedCertificatePem,
        @NotBlank String bindDn,
        String bindPassword,
        @NotBlank String changelogBaseDn,
        String branchFilterDn,
        @NotNull ChangelogFormat changelogFormat,
        boolean enabled
) {}
