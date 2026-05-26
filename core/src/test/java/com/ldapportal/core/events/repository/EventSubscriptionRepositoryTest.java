// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.repository;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EventSubscriptionRepositoryTest {

    @Autowired private EventSubscriptionRepository repository;
    @Autowired private TestEntityManager testEntityManager;

    @Test
    void findAllByEnabled_filtersCorrectly() {
        repository.save(subscription("a", true));
        repository.save(subscription("b", false));
        repository.save(subscription("c", true));

        List<EventSubscription> enabled = repository.findAllByEnabledOrderByCreatedAtDesc(true);
        assertThat(enabled).extracting(EventSubscription::getName)
                .containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void optimisticLock_throwsOnStaleUpdate() {
        // Persist and flush so the entity has version=0 in the DB.
        EventSubscription saved = testEntityManager.persistFlushFind(subscription("opt-lock", true));
        UUID id = saved.getId();
        long staleVersion = saved.getVersion();

        // Detach the saved instance to use as the stale copy later.
        testEntityManager.detach(saved);

        // Load a fresh copy, mutate, and flush — DB version becomes 1.
        EventSubscription fresh = repository.findById(id).orElseThrow();
        fresh.setName("updated-by-a");
        repository.saveAndFlush(fresh);

        // saved is still at staleVersion; trying to save it must throw.
        saved.setName("updated-by-stale");
        assertThatThrownBy(() -> repository.saveAndFlush(saved))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * The native-query path {@code findActiveMatchingType} uses Postgres'
     * {@code jsonb @> jsonb} containment. H2/HSQLDB in @DataJpaTest don't
     * support this. Covered at the E2E layer (Task 16) against real Postgres.
     */
    @Test
    @Disabled("jsonb @> requires Postgres — covered in EventBackboneEndToEndTest")
    void findActiveMatchingType_respectsJsonbFilter() {}

    private static EventSubscription subscription(String name, boolean enabled) {
        EventSubscription s = new EventSubscription();
        s.setName(name);
        s.setChannelType(ChannelType.WEBHOOK);
        s.setDestinationConfig(Map.of("url", "https://example.com"));
        s.setEnabled(enabled);
        return s;
    }
}
