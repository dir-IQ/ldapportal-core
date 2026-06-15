// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "entra_sync_state")
@Getter
@Setter
@NoArgsConstructor
public class EntraSyncState {

    @Id
    @Column(name = "directory_id", nullable = false)
    private UUID directoryId;

    @Column(name = "user_delta_token", columnDefinition = "TEXT")
    private String userDeltaToken;

    @Column(name = "group_delta_token", columnDefinition = "TEXT")
    private String groupDeltaToken;

    @Column(name = "audit_last_poll")
    private OffsetDateTime auditLastPoll;

    @Column(name = "last_full_sync")
    private OffsetDateTime lastFullSync;
}
