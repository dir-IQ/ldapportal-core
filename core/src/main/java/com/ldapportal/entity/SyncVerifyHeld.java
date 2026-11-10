// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncHeldStatus;
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
 * One held reference from a sync set's last complete content verify (see
 * {@code SyncContentVerifier}). Written and read through
 * {@link com.ldapportal.ldap.sync.SyncHeldStore} with plain JDBC; mapped as an
 * entity so the schema is validated and the table exists in tests.
 */
@Entity
@Table(name = "sync_verify_held")
@IdClass(SyncVerifyHeldId.class)
@Getter
@Setter
@NoArgsConstructor
public class SyncVerifyHeld {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    @Id
    @Column(name = "seq")
    private int seq;

    /** The target DN of the member holding the reference. */
    @Column(name = "entry_dn", nullable = false, columnDefinition = "TEXT")
    private String entryDn;

    /** The member's source entry: where a dangling reference is fixed. */
    @Column(name = "source_dn", columnDefinition = "TEXT")
    private String sourceDn;

    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    @Column(name = "attribute", nullable = false, length = 256)
    private String attribute;

    /** The source DN the held value names. */
    @Column(name = "reference", nullable = false, columnDefinition = "TEXT")
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SyncHeldStatus status;
}
