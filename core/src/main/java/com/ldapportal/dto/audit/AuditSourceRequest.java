// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.audit;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create / update request for an {@link com.ldapportal.entity.AuditDataSource}.
 *
 * <p>{@code bindPassword} is plaintext — the service encrypts before persisting.
 * Pass {@code null} on update to keep the existing encrypted value.</p>
 *
 * <p>{@code slug} is the optional stable IaC external key. On {@code POST} create
 * it is auto-derived from {@code displayName} when absent; on the {@code by-slug}
 * upsert it comes from the path. It is immutable, so any value sent on a plain
 * {@code PUT /{id}} update is ignored.</p>
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
        boolean enabled,
        @Size(max = 100)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "slug must be lowercase alphanumeric segments separated by single hyphens")
        String slug
) {}
