// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.repository;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OutboxEntryRepositoryTest {

    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private EventSubscriptionRepository subscriptionRepository;

    private UUID subId;

    @BeforeEach
    void setUp() {
        EventSubscription sub = new EventSubscription();
        sub.setName("t");
        sub.setChannelType(ChannelType.WEBHOOK);
        sub.setDestinationConfig(Map.of("url", "https://example.com"));
        sub.setEnabled(true);
        subId = subscriptionRepository.save(sub).getId();
    }

    @Test
    void findAllByStatus_paginates() {
        outboxRepository.save(outboxEntry(OutboxStatus.PENDING));
        outboxRepository.save(outboxEntry(OutboxStatus.DELIVERED));
        outboxRepository.save(outboxEntry(OutboxStatus.DEAD_LETTERED));

        Page<OutboxEntry> pending = outboxRepository.findAllByStatus(
                OutboxStatus.PENDING, PageRequest.of(0, 10));
        assertThat(pending.getContent()).hasSize(1);
        assertThat(pending.getContent().get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void countByStatus_countsCorrectly() {
        outboxRepository.save(outboxEntry(OutboxStatus.PENDING));
        outboxRepository.save(outboxEntry(OutboxStatus.PENDING));
        outboxRepository.save(outboxEntry(OutboxStatus.DELIVERED));
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
    }

    /**
     * Native SQL with {@code FOR UPDATE SKIP LOCKED} is Postgres-specific.
     * H2/HSQLDB in @DataJpaTest don't support it. Covered at the E2E layer.
     */
    @Test
    @Disabled("FOR UPDATE SKIP LOCKED requires Postgres — covered in EventBackboneEndToEndTest")
    void claimBatch_transitionsStatus() {}

    @Test
    @Disabled("partial index + UPDATE syntax requires Postgres — covered in E2E")
    void resetStaleDelivering_transitionsStaleRows() {}

    private OutboxEntry outboxEntry(OutboxStatus status) {
        OutboxEntry r = new OutboxEntry();
        r.setSubscriptionId(subId);
        r.setEventId(UUID.randomUUID());
        r.setEventType("api_token.created");
        r.setOccurredAt(Instant.now());
        r.setEnvelope(Map.of("type", "api_token.created"));
        r.setStatus(status);
        r.setAttempts(0);
        r.setNextAttemptAt(Instant.now());
        return r;
    }
}
