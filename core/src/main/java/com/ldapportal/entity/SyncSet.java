// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The unit of selection + projection, 1..n per {@link SyncLink}. A sync set
 * declares which source entries are members ({@code applicability}), how they
 * are placed and transformed on the target, and the identity key that
 * correlates a source entry to its {@link Membership} row.
 *
 * <p>Phase-0 mapping only: the rich applicability / placement / transform /
 * reference-attribute / delete-policy / reconcile-cadence columns are filled in
 * a later phase. This entity carries just enough to define the table contract
 * and the membership/recompute foreign-key target.
 */
@Entity
@Table(name = "sync_set")
@Getter
@Setter
@NoArgsConstructor
public class SyncSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Base DN bounding source enumeration (a performance hint, not the selector). */
    @Column(name = "object_scope_base_dn")
    private String objectScopeBaseDn;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_scope")
    private SyncScope objectScope;

    /**
     * Attribute(s) forming the correlation key. Defaults to the source's stable
     * server id (entryUUID / objectGUID / Entra id) once the engine lands;
     * null here means "not yet configured".
     */
    @Column(name = "identity_key")
    private String identityKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
