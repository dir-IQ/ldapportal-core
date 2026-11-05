// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncDriftCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One drift item from a sync set's last complete content verify (see
 * {@code SyncContentVerifier}). Written and read through
 * {@link com.ldapportal.ldap.sync.SyncDriftStore} with plain JDBC; mapped as an
 * entity so the schema is validated and the table exists in tests.
 */
@Entity
@Table(name = "sync_verify_entry")
@IdClass(SyncVerifyEntryId.class)
@Getter
@Setter
@NoArgsConstructor
public class SyncVerifyEntry {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    @Id
    @Column(name = "seq")
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private SyncDriftCategory category;

    @Column(name = "target_dn", nullable = false, columnDefinition = "TEXT")
    private String targetDn;

    @Column(name = "source_dn", columnDefinition = "TEXT")
    private String sourceDn;

    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    /** The differing attributes as JSON (MISMATCH only). */
    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes;
}
