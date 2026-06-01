// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A persisted reconciliation finding (R-P2): one discrepancy from a
 * {@link ReconciliationRun}, in the operator review queue when
 * {@code PROPOSED}, or tied to a corrective {@link ReplicationEvent} once
 * applied. See {@code docs/plans/2026-05-31-replication-reconciliation-design.md}.
 */
@Entity
@Table(name = "reconciliation_findings")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    // ON DELETE CASCADE mirrors V14 so the retention sweep's bulk run-delete
    // takes resolved findings with it. Declared here too (not just in the
    // migration) so the create-drop schema used by tests matches Flyway.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ReconciliationRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ReplicationLink link;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 30)
    private ReconciliationFindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_op", nullable = false, length = 20)
    private ReplicationOperationType suggestedOp;

    @Column(name = "source_dn", length = 2000)
    private String sourceDn;

    @Column(name = "target_dn", nullable = false, length = 2000)
    private String targetDn;

    /** Render-and-encode payload; UI-only keys (before/currentTarget) may be present. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationFindingStatus status;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
