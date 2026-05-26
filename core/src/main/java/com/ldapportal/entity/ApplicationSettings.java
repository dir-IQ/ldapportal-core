// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.SiemFormat;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.entity.enums.SslMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Global application settings (§10.2).
 * Covers branding, session timeout, SMTP mail relay, S3-compatible storage,
 * and admin authentication configuration.  Exactly one singleton row.
 */
@Entity
@Table(name = "application_settings")
@Getter
@Setter
@NoArgsConstructor
public class ApplicationSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    // ── Branding ──────────────────────────────────────────────────────────────

    @Column(name = "app_name", nullable = false)
    private String appName = "LDAP Portal";

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "primary_colour", length = 20)
    private String primaryColour;

    @Column(name = "secondary_colour", length = 20)
    private String secondaryColour;

    // ── Approval workflow ──────────────────────────────────────────────────────

    /** When true, superadmins bypass approval and their requests are auto-approved. */
    @Column(name = "superadmin_bypass_approval", nullable = false)
    private boolean superadminBypassApproval = false;

    /**
     * When true, the Directory Search results table shows an "Edit
     * results" toggle that switches eligible cells into editable
     * inputs (the Phase 1 inline-edit feature). When false the
     * affordance is hidden — the typed update endpoints stay
     * reachable for other consumers, but the UI surface is gone.
     * Defaults true; existing installs preserve the behaviour they
     * got at upgrade time.
     */
    @Column(name = "directory_search_inline_edit_enabled", nullable = false)
    private boolean directorySearchInlineEditEnabled = true;

    // ── Session ───────────────────────────────────────────────────────────────

    @Column(name = "session_timeout_minutes", nullable = false)
    private int sessionTimeoutMinutes = 60;

    // ── SMTP mail relay ───────────────────────────────────────────────────────

    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort = 587;

    @Column(name = "smtp_sender_address")
    private String smtpSenderAddress;

    @Column(name = "smtp_username")
    private String smtpUsername;

    /** AES-256 encrypted SMTP password. */
    @Column(name = "smtp_password_encrypted", columnDefinition = "TEXT")
    private String smtpPasswordEncrypted;

    @Column(name = "smtp_use_tls", nullable = false)
    private boolean smtpUseTls = true;

    // ── S3-compatible object storage ──────────────────────────────────────────

    @Column(name = "s3_endpoint_url")
    private String s3EndpointUrl;

    @Column(name = "s3_bucket_name")
    private String s3BucketName;

    @Column(name = "s3_access_key")
    private String s3AccessKey;

    /** AES-256 encrypted S3 secret key. */
    @Column(name = "s3_secret_key_encrypted", columnDefinition = "TEXT")
    private String s3SecretKeyEncrypted;

    @Column(name = "s3_region")
    private String s3Region;

    /** TTL for pre-signed download links in hours (default 24 h per §7.2). */
    @Column(name = "s3_presigned_url_ttl_hours", nullable = false)
    private int s3PresignedUrlTtlHours = 24;

    // ── Admin authentication configuration ─────────────────────────────────────

    /**
     * Set of authentication methods enabled for admin logins.
     * Controls which login UI elements the frontend shows (password form, SSO button, or both).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "enabled_auth_types", joinColumns = @JoinColumn(name = "settings_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 10)
    private Set<AccountType> enabledAuthTypes = new HashSet<>(Set.of(AccountType.LOCAL));

    // ── LDAP auth provider ───────────────────────────────────────────────────

    @Column(name = "ldap_auth_host")
    private String ldapAuthHost;

    @Column(name = "ldap_auth_port")
    private Integer ldapAuthPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "ldap_auth_ssl_mode", length = 10)
    private SslMode ldapAuthSslMode;

    @Column(name = "ldap_auth_trust_all_certs", nullable = false)
    private boolean ldapAuthTrustAllCerts = false;

    @Column(name = "ldap_auth_trusted_cert_pem", columnDefinition = "TEXT")
    private String ldapAuthTrustedCertPem;

    /** Optional service-account bind DN for user lookup (may be null). */
    @Column(name = "ldap_auth_bind_dn", length = 500)
    private String ldapAuthBindDn;

    /** AES-256-GCM encrypted service-account bind password. */
    @Column(name = "ldap_auth_bind_password_enc", columnDefinition = "TEXT")
    private String ldapAuthBindPasswordEnc;

    @Column(name = "ldap_auth_user_search_base", length = 500)
    private String ldapAuthUserSearchBase;

    /**
     * Pattern used to construct the user bind DN at authentication time.
     * {@code {username}} is substituted with the supplied username.
     * Example: {@code uid={username},ou=people,dc=example,dc=com}
     */
    @Column(name = "ldap_auth_bind_dn_pattern", length = 500)
    private String ldapAuthBindDnPattern;

    // ── OIDC auth provider ───────────────────────────────────────────────────

    /** OIDC issuer URL, e.g. https://accounts.google.com */
    @Column(name = "oidc_issuer_url", length = 1000)
    private String oidcIssuerUrl;

    @Column(name = "oidc_client_id", length = 500)
    private String oidcClientId;

    /** AES-256-GCM encrypted OIDC client secret. */
    @Column(name = "oidc_client_secret_enc", columnDefinition = "TEXT")
    private String oidcClientSecretEnc;

    @Column(name = "oidc_scopes", length = 500)
    private String oidcScopes = "openid profile email";

    /** Claim from the ID token used to match against Account.username. */
    @Column(name = "oidc_username_claim", length = 100)
    private String oidcUsernameClaim = "preferred_username";

    /**
     * Configured redirect URI for the OIDC callback. When null the server
     * falls back to deriving the URI from the inbound HTTP request, which
     * relies on the (spoofable) Host header — set this explicitly in any
     * production deployment.
     */
    @Column(name = "oidc_redirect_uri", columnDefinition = "TEXT")
    private String oidcRedirectUri;

    // ── WebSEAL (IBM Verify Identity Access) header-based SSO ───────────────

    /**
     * Newline-separated list of CIDRs. The WebSEAL pre-auth path only trusts
     * inbound requests whose peer IP ({@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr})
     * falls inside one of these ranges. Empty/null effectively disables the
     * feature even when WEBSEAL is in {@link #enabledAuthTypes}.
     */
    @Column(name = "webseal_trusted_proxies", columnDefinition = "TEXT")
    private String websealTrustedProxies;

    @Column(name = "webseal_user_header", length = 100)
    private String websealUserHeader = "iv-user";

    @Column(name = "webseal_groups_header", length = 100)
    private String websealGroupsHeader = "iv-groups";

    /** URL (absolute or path) the frontend is asked to redirect to on logout
     *  when the current user is WEBSEAL-authenticated. Defaults to WebSEAL's
     *  standard sign-off path. */
    @Column(name = "webseal_logout_url", length = 500)
    private String websealLogoutUrl = "/pkmslogout";

    // ── SIEM / syslog export ────────────────────────────────────────────────

    @Column(name = "siem_enabled", nullable = false)
    private boolean siemEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "siem_protocol", length = 20)
    private SiemProtocol siemProtocol;

    @Column(name = "siem_host", length = 500)
    private String siemHost;

    @Column(name = "siem_port")
    private Integer siemPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "siem_format", length = 20)
    private SiemFormat siemFormat;

    /** AES-256 encrypted bearer token for syslog TLS auth. */
    @Column(name = "siem_auth_token_enc", columnDefinition = "TEXT")
    private String siemAuthTokenEnc;

    @Column(name = "webhook_url", length = 2000)
    private String webhookUrl;

    /** AES-256 encrypted Authorization header value for webhook. */
    @Column(name = "webhook_auth_header_enc", columnDefinition = "TEXT")
    private String webhookAuthHeaderEnc;

    // ── Setup wizard ──────────────────────────────────────────────────────

    @Column(name = "setup_completed", nullable = false)
    private boolean setupCompleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
