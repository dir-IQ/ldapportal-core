// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One attribute-rename / value-template rule for a replication link.
 * Identity mapping (no rename, no transform) is represented by the
 * absence of any row for a given attribute — not by an explicit
 * identity row. So the table is small for the common case.
 *
 * <p>Composite key on {@code (linkId, sourceAttr)} prevents two
 * conflicting rules for the same source attribute on the same link.
 */
@Entity
@Table(name = "replication_link_attr_mappings")
@IdClass(ReplicationLinkAttrMapping.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class ReplicationLinkAttrMapping {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private ReplicationLink link;

    @Id
    @Column(name = "source_attr", nullable = false, length = 255)
    private String sourceAttr;

    @Column(name = "target_attr", nullable = false, length = 255)
    private String targetAttr;

    /**
     * Optional value-template with a single {@code ${value}} substitution
     * token. NULL = identity (pass through unchanged). Examples:
     * <ul>
     *   <li>{@code "${value}@corp.com"} — appends a domain suffix.</li>
     *   <li>{@code "[${value}]"} — wraps in brackets.</li>
     * </ul>
     */
    @Column(name = "value_template", length = 2000)
    private String valueTemplate;

    public static class Key implements Serializable {
        private UUID link;
        private String sourceAttr;

        public Key() {}
        public Key(UUID link, String sourceAttr) {
            this.link = link;
            this.sourceAttr = sourceAttr;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(link, k.link) && Objects.equals(sourceAttr, k.sourceAttr);
        }
        @Override public int hashCode() {
            return Objects.hash(link, sourceAttr);
        }
    }
}
