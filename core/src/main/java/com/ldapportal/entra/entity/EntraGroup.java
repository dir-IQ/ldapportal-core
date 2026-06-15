// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "entra_groups")
@Getter
@Setter
@NoArgsConstructor
public class EntraGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "directory_id", nullable = false)
    private UUID directoryId;

    @Column(name = "entra_object_id", nullable = false, length = 100)
    private String entraObjectId;

    @Column(name = "display_name", length = 500)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt = OffsetDateTime.now();
}
