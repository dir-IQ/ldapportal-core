// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.MembershipState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The membership index: one row per in-scope source identity, per
 * {@link SyncSet}. This is the materialized "shadow" / connector table at the
 * heart of the sync engine — a cache of recomputable truth (the source
 * directory is the truth) that lets every target operation be derived from a
 * <em>membership transition</em> rather than from interpreting a raw change
 * record.
 *
 * <p>Phase-0 mapping only: the engine that reads, diffs, and applies these
 * rows arrives in a later phase. Lose the index → reconcile rebuilds it.
 */
@Entity
@Table(name = "sync_membership")
@IdClass(MembershipId.class)
@Getter
@Setter
@NoArgsConstructor
public class Membership {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    /** Normalized correlation-key value (the stable source identity). */
    @Id
    @Column(name = "identity")
    private String identity;

    /** Current source DN (changes on rename/move; the identity does not). */
    @Column(name = "source_dn", nullable = false)
    private String sourceDn;

    /** Where the entry was placed on the target. */
    @Column(name = "target_dn", nullable = false)
    private String targetDn;

    /** Hash of the projected desired target state — drives churn suppression. */
    @Column(name = "content_hash", nullable = false)
    private byte[] contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private MembershipState state;

    @Column(name = "fail_reason")
    private String failReason;

    /** changeNumber / USN / seq that last touched this identity. */
    @Column(name = "last_src_cursor")
    private Long lastSrcCursor;

    /** Reconcile sweep generation that last confirmed this row. */
    @Column(name = "last_scan_epoch")
    private Long lastScanEpoch;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
