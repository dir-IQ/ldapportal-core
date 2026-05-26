// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ChangelogFormat;
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
@Table(name = "audit_data_sources")
@Getter
@Setter
@NoArgsConstructor
public class AuditDataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port = 389;

    @Enumerated(EnumType.STRING)
    @Column(name = "ssl_mode", nullable = false, length = 10)
    private SslMode sslMode = SslMode.NONE;

    @Column(name = "trust_all_certs", nullable = false)
    private boolean trustAllCerts = false;

    @Column(name = "trusted_certificate_pem", columnDefinition = "TEXT")
    private String trustedCertificatePem;

    @Column(name = "bind_dn", nullable = false)
    private String bindDn;

    /** AES-256 encrypted bind password. */
    @Column(name = "bind_password_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bindPasswordEncrypted;

    /** Base DN for changelog reads, e.g. {@code cn=changelog}. */
    @Column(name = "changelog_base_dn", nullable = false)
    private String changelogBaseDn = "cn=changelog";

    /** Changelog format: DSEE ({@code cn=changelog}) or OpenLDAP accesslog. */
    @Enumerated(EnumType.STRING)
    @Column(name = "changelog_format", nullable = false, length = 25)
    private ChangelogFormat changelogFormat = ChangelogFormat.DSEE_CHANGELOG;

    /** Optional: restrict changelog reads to entries under this targetDN subtree. */
    @Column(name = "branch_filter_dn")
    private String branchFilterDn;

    @Column(nullable = false)
    private boolean enabled = true;

    /** DirSync cookie for AD incremental polling. */
    @Column(name = "dirsync_cookie")
    private byte[] dirsyncCookie;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
