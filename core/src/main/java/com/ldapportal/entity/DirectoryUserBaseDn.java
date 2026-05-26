// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "directory_user_base_dns")
@Getter
@Setter
@NoArgsConstructor
public class DirectoryUserBaseDn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "directory_id", nullable = false)
    private DirectoryConnection directory;

    @Column(nullable = false)
    private String dn;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /** When {@code false} the branch is read-only; write operations are rejected. */
    @Column(nullable = false)
    private boolean editable = false;
}
