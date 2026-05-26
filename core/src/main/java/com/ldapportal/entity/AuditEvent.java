// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Single audit event stored in {@code audit_events}.
 *
 * <p>Populated by two paths:</p>
 * <ul>
 *   <li>{@code INTERNAL} — emitted by {@link com.ldapportal.service.AuditService}
 *       after every write operation in
 *       {@link com.ldapportal.service.LdapOperationService}.</li>
 *   <li>{@code LDAP_CHANGELOG} — produced by
 *       {@link com.ldapportal.ldap.LdapChangelogReader} when it polls
 *       {@code cn=changelog} on a configured {@link AuditDataSource}.</li>
 * </ul>
 *
 * <p>Actor and directory fields are denormalised so records survive account
 * or directory deletion.</p>
 */
@Entity
@Table(name = "audit_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AuditSource source;

    // ── Actor ─────────────────────────────────────────────────────────────────

    /** UUID of the admin account; {@code null} for LDAP_CHANGELOG events. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    /** {@code "ADMIN"} or {@code "SUPERADMIN"}; {@code null} for changelog. */
    @Column(name = "actor_type", updatable = false, length = 20)
    private String actorType;

    /** Denormalised username for historical records. */
    @Column(name = "actor_username", updatable = false)
    private String actorUsername;

    // ── Directory ─────────────────────────────────────────────────────────────

    @Column(name = "directory_id", updatable = false)
    private UUID directoryId;

    /** Denormalised for history. */
    @Column(name = "directory_name", updatable = false)
    private String directoryName;

    // ── Event payload ─────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private AuditAction action;

    @Column(name = "target_dn", updatable = false, length = 2000)
    private String targetDn;

    /**
     * Free-form JSONB detail.  For INTERNAL events this holds the attribute
     * names relevant to the operation.  For changelog events it holds the raw
     * change attributes from {@code cn=changelog}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> detail;

    /** {@code changeNumber} attribute from {@code cn=changelog}. */
    @Column(name = "changelog_change_number", updatable = false)
    private String changelogChangeNumber;

    /** When the underlying LDAP operation actually occurred. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    /** When LDAPPortal recorded this event. */
    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;
}
