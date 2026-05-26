// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.EnableDisableValueType;
import com.ldapportal.entity.enums.SslMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "directory_connections")
@Getter
@Setter
@NoArgsConstructor
public class DirectoryConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "directory_type", nullable = false, length = 20)
    private DirectoryType directoryType = DirectoryType.GENERIC;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port = 389;

    @Enumerated(EnumType.STRING)
    @Column(name = "ssl_mode", nullable = false, length = 10)
    private SslMode sslMode = SslMode.NONE;

    @Column(name = "trust_all_certs", nullable = false)
    private boolean trustAllCerts = false;

    /** PEM-encoded CA certificate for custom trust anchors. */
    @Column(name = "trusted_certificate_pem", columnDefinition = "TEXT")
    private String trustedCertificatePem;

    @Column(name = "bind_dn", nullable = false)
    private String bindDn;

    /** AES-256 encrypted bind password. Encryption key never stored in DB. */
    @Column(name = "bind_password_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bindPasswordEncrypted;

    @Column(name = "base_dn", nullable = false)
    private String baseDn;

    @Column(name = "paging_size", nullable = false)
    private int pagingSize = 500;

    @Column(name = "pool_min_size", nullable = false)
    private int poolMinSize = 2;

    @Column(name = "pool_max_size", nullable = false)
    private int poolMaxSize = 20;

    @Column(name = "pool_connect_timeout_seconds", nullable = false)
    private int poolConnectTimeoutSeconds = 10;

    @Column(name = "pool_response_timeout_seconds", nullable = false)
    private int poolResponseTimeoutSeconds = 30;

    // ── Account enable/disable attribute configuration (§4.1 / OI-001) ────────

    /** LDAP attribute name representing account enabled/disabled state. */
    @Column(name = "enable_disable_attribute")
    private String enableDisableAttribute;

    /** Whether the attribute is a boolean toggle or a string value. */
    @Enumerated(EnumType.STRING)
    @Column(name = "enable_disable_value_type", length = 10)
    private EnableDisableValueType enableDisableValueType = EnableDisableValueType.STRING;

    /** Value to write to the attribute when enabling the account. */
    @Column(name = "enable_value")
    private String enableValue;

    /** Value to write to the attribute when disabling the account. */
    @Column(name = "disable_value")
    private String disableValue;

    // ── Self-service portal ──────────────────────────────────────────────────

    @Column(name = "self_service_enabled", nullable = false)
    private boolean selfServiceEnabled = false;

    @Column(name = "self_service_login_attribute", length = 64)
    private String selfServiceLoginAttribute = "uid";

    // ── Application user repository ───────────────────────────────────────────

    /**
     * When {@code true} this connection is the authoritative store for
     * application user accounts (login accounts for the portal itself).
     * At most one directory should be flagged as the user repository.
     */
    @Column(name = "is_user_repository", nullable = false)
    private boolean userRepository = false;

    /**
     * DN of the LDAP container in which new application user entries are created.
     * Required when {@code userRepository} is {@code true}.
     */
    @Column(name = "user_creation_base_dn")
    private String userCreationBaseDn;

    // ── Audit / changelog source ──────────────────────────────────────────────

    /**
     * Optional reference to the changelog reader connection for this directory.
     * FK constraint was added in V4 after audit_data_sources was created.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_data_source_id")
    private AuditDataSource auditDataSource;

    // ── Entra ID (Azure AD) connection fields ──────────────────────────────

    /** Microsoft Entra tenant ID (UUID format). */
    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    /** Entra ID app registration client ID. */
    @Column(name = "entra_client_id", length = 100)
    private String entraClientId;

    /** AES-256 encrypted Entra client secret. */
    @Column(name = "entra_client_secret_encrypted", columnDefinition = "TEXT")
    private String entraClientSecretEncrypted;

    /** Graph API base endpoint (default https://graph.microsoft.com). */
    @Column(name = "graph_endpoint", length = 255)
    private String graphEndpoint = "https://graph.microsoft.com";

    // ── Multi-DC failover (AD) ──────────────────────────────────────────────

    @Column(name = "secondary_host")
    private String secondaryHost;

    @Column(name = "secondary_port")
    private Integer secondaryPort;

    @Column(name = "global_catalog_port")
    private Integer globalCatalogPort;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
