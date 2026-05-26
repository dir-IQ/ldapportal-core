// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dismissed_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class DismissedSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "suggestion_key", nullable = false, length = 200)
    private String suggestionKey;

    @Column(name = "dismissed_at", nullable = false)
    private OffsetDateTime dismissedAt = OffsetDateTime.now();
}
