// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.settings;

import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.SiemFormat;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.entity.enums.SslMode;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Read DTO for global application settings.
 * Encrypted credential fields are never returned; instead boolean flags
 * indicate whether a password/secret has been configured.
 */
public record ApplicationSettingsDto(
        UUID id,

        // Branding
        String appName,
        String logoUrl,
        String primaryColour,
        String secondaryColour,

        // User/Group edits
        boolean directorySearchInlineEditEnabled,

        // Session
        int sessionTimeoutMinutes,

        // SMTP
        String smtpHost,
        Integer smtpPort,
        String smtpSenderAddress,
        String smtpUsername,
        boolean smtpPasswordConfigured,
        boolean smtpUseTls,

        // S3
        String s3EndpointUrl,
        String s3BucketName,
        String s3AccessKey,
        boolean s3SecretKeyConfigured,
        String s3Region,
        int s3PresignedUrlTtlHours,

        // Authentication
        Set<AccountType> enabledAuthTypes,

        // LDAP auth provider
        String ldapAuthHost,
        Integer ldapAuthPort,
        SslMode ldapAuthSslMode,
        boolean ldapAuthTrustAllCerts,
        String ldapAuthTrustedCertPem,
        String ldapAuthBindDn,
        boolean ldapAuthBindPasswordConfigured,
        String ldapAuthUserSearchBase,
        String ldapAuthBindDnPattern,

        // OIDC auth provider
        String oidcIssuerUrl,
        String oidcClientId,
        boolean oidcClientSecretConfigured,
        String oidcScopes,
        String oidcUsernameClaim,
        String oidcRedirectUri,

        // SIEM / syslog export
        boolean siemEnabled,
        SiemProtocol siemProtocol,
        String siemHost,
        Integer siemPort,
        SiemFormat siemFormat,
        boolean siemAuthTokenConfigured,
        String webhookUrl,
        boolean webhookAuthHeaderConfigured,

        // WebSEAL (IBM Verify Identity Access) header-based SSO
        String websealTrustedProxies,
        String websealUserHeader,
        String websealGroupsHeader,
        String websealLogoutUrl,

        // Auth-toggle UI visibility — config-derived (app.auth.*.ui-visible),
        // read-only, not persisted. Drives whether the OIDC / WebSEAL toggle
        // and config block are shown in the Authentication tab.
        boolean oidcToggleVisible,
        boolean websealToggleVisible,

        // Setup wizard
        boolean setupCompleted,

        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
