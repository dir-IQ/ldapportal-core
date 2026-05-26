// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Individual column mapping within a {@link CsvMappingTemplate}.
 */
@Entity
@Table(
    name = "csv_mapping_template_entries",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_csv_entry_template_col",
        columnNames = {"template_id", "csv_column_name"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CsvMappingTemplateEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private CsvMappingTemplate template;

    /** Header name from the CSV file. */
    @Column(name = "csv_column_name", nullable = false)
    private String csvColumnName;

    /** 0-based column index; optional fallback when no header row is present. */
    @Column(name = "csv_column_index")
    private Integer csvColumnIndex;

    /**
     * Target LDAP attribute name.
     * {@code null} when {@code ignored = true}.
     */
    @Column(name = "ldap_attribute")
    private String ldapAttribute;

    /** When {@code true} this column is present in the CSV but discarded on import. */
    @Column(nullable = false)
    private boolean ignored = false;
}
