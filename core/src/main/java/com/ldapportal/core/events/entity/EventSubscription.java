// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.entity;

import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.entity.Account;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A named outbound-event destination. The transport (WebhookChannel today)
 * is picked by {@link #channelType}; {@link #destinationConfig} holds the
 * transport's settings (URL + optional encrypted auth blob).
 *
 * <p>Secrets inside {@code destinationConfig} are keyed {@code *Enc} and
 * carry ciphertext produced by the existing {@code EncryptionService}.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-04-23-event-backbone-design.md}.</p>
 */
@Entity
@Table(name = "event_subscription")
@Getter
@Setter
@NoArgsConstructor
public class EventSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ChannelType channelType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "destination_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> destinationConfig;

    /** Null = all events; non-null = list of {@code OutboundEventType.wireName()} strings. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_type_filter", columnDefinition = "jsonb")
    private List<String> eventTypeFilter;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private Account createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
