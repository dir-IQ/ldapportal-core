// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "entra_group_memberships")
@Getter
@Setter
@NoArgsConstructor
public class EntraGroupMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "directory_id", nullable = false)
    private UUID directoryId;

    @Column(name = "user_object_id", nullable = false, length = 100)
    private String userObjectId;

    @Column(name = "group_object_id", nullable = false, length = 100)
    private String groupObjectId;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt = OffsetDateTime.now();
}
