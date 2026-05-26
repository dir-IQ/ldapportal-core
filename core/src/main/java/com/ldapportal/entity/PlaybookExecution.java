// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.PlaybookExecutionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "playbook_executions")
@Getter
@Setter
@NoArgsConstructor
public class PlaybookExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playbook_id", nullable = false)
    private LifecyclePlaybook playbook;

    @Column(name = "target_dn", nullable = false, length = 500)
    private String targetDn;

    @Column(name = "executed_by")
    private UUID executedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlaybookExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_results", columnDefinition = "jsonb", nullable = false)
    private String stepResults = "[]";

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
