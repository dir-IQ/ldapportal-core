// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.PlaybookStepAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "playbook_steps")
@Getter
@Setter
@NoArgsConstructor
public class PlaybookStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playbook_id", nullable = false)
    private LifecyclePlaybook playbook;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PlaybookStepAction action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String parameters = "{}";

    @Column(name = "continue_on_error", nullable = false)
    private boolean continueOnError = false;
}
