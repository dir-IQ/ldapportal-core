// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The coalescing recompute queue: every change feed and reconciliation reduce
 * to "identity/DN X may have changed → enqueue a recompute." The composite PK
 * {@code (sync_set_id, request_key)} dedups bursts for free; an upsert keeps
 * the max source cursor.
 *
 * <p>Phase-0 mapping only: the worker that drains this queue arrives in a later
 * phase. {@code request_key} is an identity or a source DN.
 */
@Entity
@Table(name = "recompute_request")
@IdClass(RecomputeRequestId.class)
@Getter
@Setter
@NoArgsConstructor
public class RecomputeRequest {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    @Id
    @Column(name = "request_key")
    private String requestKey;

    /** Highest source cursor seen for this key; lets behind-cursor triggers drop. */
    @Column(name = "src_cursor")
    private Long srcCursor;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @CreationTimestamp
    @Column(name = "enqueued_at", nullable = false, updatable = false)
    private OffsetDateTime enqueuedAt;
}
