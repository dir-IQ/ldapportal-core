// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5 retention: verifies the two JPQL delete queries behind
 * {@link ReplicationEventRetentionScheduler} against a real JPA session
 * (H2 under the {@code test} profile), with fixtures spanning
 * status × age × link-enabled. The Mockito test
 * ({@link ReplicationEventRetentionSchedulerTest}) covers the scheduler's
 * cutoff arithmetic and error-swallowing; this one covers the SQL.
 *
 * <p>Reuses the entitled-license {@code @TestConfiguration} from
 * {@link ReplicationPersistenceIntegrationTest} so the Spring context is
 * identical to (and cached/shared with) that integration test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ReplicationPersistenceIntegrationTest.DirectorySyncEntitledConfig.class)
class ReplicationRetentionIntegrationTest {

    @Autowired private ReplicationLinkRepository     linkRepo;
    @Autowired private ReplicationEventRepository    eventRepo;
    @Autowired private DirectoryConnectionRepository dirRepo;
    @Autowired private TransactionTemplate           txTemplate;
    @Autowired private JdbcTemplate                  jdbc;

    private UUID enabledLinkId;
    private UUID disabledLinkId;

    @BeforeEach
    void setup() {
        // No @Transactional on the test, so each delete commits. Order
        // matters: events → links → dirs.
        eventRepo.deleteAll();
        linkRepo.deleteAll();
        dirRepo.deleteAll();
        txTemplate.executeWithoutResult(tx -> {
            UUID src  = dirRepo.save(buildDir("src")).getId();
            UUID tgtA = dirRepo.save(buildDir("tgtA")).getId();
            UUID tgtB = dirRepo.save(buildDir("tgtB")).getId();
            // Distinct targets so the two links don't collide on any
            // (source,target) uniqueness, and to sidestep bidirectional rules.
            enabledLinkId  = saveLink(src, tgtA, true);
            disabledLinkId = saveLink(src, tgtB, false);
        });
    }

    @Test
    void floor_deletesOldDeliveredOnEnabledLinks_keepsRecentNonDeliveredAndDisabled() {
        OffsetDateTime now = OffsetDateTime.now();
        insertEvent(enabledLinkId, ReplicationEventStatus.DELIVERED, now.minusDays(40), now.minusDays(40));   // → deleted
        UUID recentDelivered  = insertEvent(enabledLinkId,  ReplicationEventStatus.DELIVERED,     now.minusDays(5),  now.minusDays(5));
        UUID oldDeadLettered  = insertEvent(enabledLinkId,  ReplicationEventStatus.DEAD_LETTERED, now.minusDays(40), null);
        UUID oldDeliveredDisabled = insertEvent(disabledLinkId, ReplicationEventStatus.DELIVERED, now.minusDays(40), now.minusDays(40));

        int deleted = eventRepo.deleteDeliveredForEnabledLinksOlderThan(now.minusDays(30));

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingIds()).containsExactlyInAnyOrder(
                recentDelivered, oldDeadLettered, oldDeliveredDisabled);
    }

    @Test
    void cap_deletesAnythingEnqueuedBeforeCutoff_regardlessOfStatus() {
        OffsetDateTime now = OffsetDateTime.now();
        insertEvent(enabledLinkId, ReplicationEventStatus.DELIVERED,     now.minusDays(100), now.minusDays(99)); // → deleted
        insertEvent(enabledLinkId, ReplicationEventStatus.DEAD_LETTERED, now.minusDays(100), null);              // → deleted
        UUID recentPending = insertEvent(enabledLinkId, ReplicationEventStatus.PENDING, now.minusDays(10), null);

        int deleted = eventRepo.deleteEnqueuedBefore(now.minusDays(90));

        assertThat(deleted).isEqualTo(2);
        assertThat(remainingIds()).containsExactly(recentPending);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private Set<UUID> remainingIds() {
        return eventRepo.findAll().stream().map(ReplicationEvent::getId).collect(Collectors.toSet());
    }

    private UUID saveLink(UUID srcId, UUID tgtId, boolean enabled) {
        ReplicationLink link = new ReplicationLink();
        link.setDisplayName(enabled ? "enabled-link" : "disabled-link");
        link.setSourceDirectory(dirRepo.findById(srcId).orElseThrow());
        link.setTargetDirectory(dirRepo.findById(tgtId).orElseThrow());
        link.setEnabled(enabled);
        return linkRepo.save(link).getId();
    }

    private UUID insertEvent(UUID linkId, ReplicationEventStatus status,
                             OffsetDateTime enqueuedAt, OffsetDateTime deliveredAt) {
        UUID id = txTemplate.execute(tx -> {
            ReplicationEvent e = new ReplicationEvent();
            e.setLink(linkRepo.findById(linkId).orElseThrow());
            e.setOperation(ReplicationOperationType.MODIFY);
            e.setSourceDn("uid=a,dc=src,dc=com");
            e.setTargetDn("uid=a,dc=tgt,dc=com");
            e.setStatus(status);
            e.setPayload(Map.of("modifications", List.of()));
            return eventRepo.save(e).getId();
        });
        // enqueued_at is @Column(updatable = false) and delivered_at is set by
        // the worker, not on insert — so a managed-entity merge can't backdate
        // them. Set both age columns via native SQL for the fixtures.
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE replication_events SET enqueued_at = ?, delivered_at = ? WHERE id = ?");
            ps.setObject(1, enqueuedAt);
            ps.setObject(2, deliveredAt);
            ps.setObject(3, id);
            return ps;
        });
        return id;
    }

    private static DirectoryConnection buildDir(String label) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDisplayName("dir-" + label);
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setHost(label + ".example.com");
        dc.setPort(389);
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn("cn=admin,dc=" + label);
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn("dc=" + label + ",dc=com");
        dc.setPagingSize(500);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolConnectTimeoutSeconds(10);
        dc.setPoolResponseTimeoutSeconds(30);
        return dc;
    }
}
