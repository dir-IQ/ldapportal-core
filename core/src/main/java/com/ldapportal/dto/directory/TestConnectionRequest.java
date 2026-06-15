// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

import com.ldapportal.entity.enums.SslMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Standalone LDAP connection test (not persisted).
 * The bind password is plaintext; it is never stored.
 */
public record TestConnectionRequest(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotNull SslMode sslMode,
        boolean trustAllCerts,
        String trustedCertificatePem,
        @NotBlank String bindDn,
        @NotBlank String bindPassword) {
}
