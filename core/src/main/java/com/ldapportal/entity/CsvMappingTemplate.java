// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ConflictHandling;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Named CSV column-to-LDAP-attribute mapping template per directory (§7.1, §10.2).
 * Saved templates can be selected on future imports to avoid re-mapping columns.
 */
@Entity
@Table(
    name = "csv_mapping_templates",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_csv_template_dir_name",
        columnNames = {"directory_id", "name"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CsvMappingTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "directory_id", nullable = false)
    private DirectoryConnection directory;

    @Column(nullable = false)
    private String name;

    /** LDAP attribute used to match CSV rows against existing directory entries. */
    @Column(name = "target_key_attribute", nullable = false)
    private String targetKeyAttribute = "uid";

    /** Default conflict resolution when a matching entry already exists. */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_handling", nullable = false, length = 20)
    private ConflictHandling conflictHandling = ConflictHandling.PROMPT;

    /** The LDAP objectClass whose attributes define the column mappings. */
    @Column(name = "object_class")
    private String objectClass;

    /** Whether to treat the first row of the CSV as column headers (true) or data (false). */
    @Column(name = "skip_header_row", nullable = false)
    private boolean skipHeaderRow = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
