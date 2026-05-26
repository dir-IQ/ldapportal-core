// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.repository;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventSubscriptionRepository extends JpaRepository<EventSubscription, UUID> {

    @Override
    @EntityGraph(attributePaths = "createdBy")
    Optional<EventSubscription> findById(UUID id);

    @EntityGraph(attributePaths = "createdBy")
    List<EventSubscription> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "createdBy")
    List<EventSubscription> findAllByEnabledOrderByCreatedAtDesc(boolean enabled);

    @EntityGraph(attributePaths = "createdBy")
    List<EventSubscription> findAllByChannelTypeOrderByCreatedAtDesc(ChannelType channelType);

    @EntityGraph(attributePaths = "createdBy")
    List<EventSubscription> findAllByEnabledAndChannelTypeOrderByCreatedAtDesc(
            boolean enabled, ChannelType channelType);

    /**
     * Active subscriptions whose {@code event_type_filter} is NULL (matches all)
     * OR contains the given wire-name (matches specifically). JSONB containment
     * via {@code jsonb @> '["name"]'}.
     */
    @Query(value = """
        SELECT * FROM event_subscription
         WHERE enabled = true
           AND (event_type_filter IS NULL
                OR event_type_filter @> CAST(:filterJson AS jsonb))
         ORDER BY created_at DESC
        """, nativeQuery = true)
    List<EventSubscription> findActiveMatchingType(@Param("filterJson") String filterJson);
}
