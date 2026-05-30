// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Source → target replication configuration. One row per directional
 * pair. Attribute-mapping rules attach via the
 * {@link ReplicationLinkAttrMapping} child rows.
 *
 * <p>See {@code docs/plans/2026-05-30-directory-sync-design.md}.
 */
@Entity
@Table(name = "replication_links")
@Getter
@Setter
@NoArgsConstructor
public class ReplicationLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_dir_id", nullable = false)
    private DirectoryConnection sourceDirectory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_dir_id", nullable = false)
    private DirectoryConnection targetDirectory;

    /**
     * Source-side base DN for DN rewriting. NULL pair (with
     * {@link #targetBaseDn}) means identity mapping — source and target
     * use the same DN. Both must be NULL or both set, enforced by a
     * DB CHECK constraint.
     */
    @Column(name = "source_base_dn", length = 500)
    private String sourceBaseDn;

    @Column(name = "target_base_dn", length = 500)
    private String targetBaseDn;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "auto_create_on_missing", nullable = false)
    private boolean autoCreateOnMissing = false;

    @OneToMany(mappedBy = "link", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<ReplicationLinkAttrMapping> attributeMappings = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
