// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.Edition;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.License;
import com.ldapportal.core.entitlement.LicenseProvider;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-shape integration test for the replication persistence
 * surface. The Mockito unit tests around this subsystem hand the
 * enqueuer / worker plain POJOs and never exercise the JPA session
 * lifecycle — which is exactly where two confirmed-CVE-class bugs
 * landed and shipped to a code-review pass before this test existed:
 *
 * <ol>
 *   <li>{@code ReplicationEnqueuer.enqueue()} accessing the LAZY
 *       {@code link.attributeMappings} collection outside any
 *       Hibernate session (because the {@code @Transactional} was
 *       intentionally lifted off the hot path) silently lost every
 *       ADD/MODIFY when the link had mapping rules.</li>
 *   <li>{@code resetStaleInFlight} keying on {@code enqueued_at}
 *       instead of claim time false-reset any backlog event the
 *       instant a worker claimed it, opening a double-delivery
 *       window.</li>
 * </ol>
 *
 * <p>The defining characteristic of these tests: the
 * {@code @Transactional} on the test method itself is deliberately
 * absent (and Spring's per-test-rollback default disabled) so the
 * production session-boundary semantics are reproduced — setup
 * commits, the unit-under-test runs outside any caller tx, and we
 * assert against a fresh read.
 *
 * <p>R2: the enqueuer drops captured writes when {@code DIRECTORY_SYNC}
 * isn't entitled. The {@code test} profile loads the community-shape
 * context (no license), so this test supplies a {@code @Primary}
 * all-entitlements {@link LicenseProvider} — a real bean, so the
 * entitlement startup reporter still works — to exercise the enqueue
 * path. Registered via {@code @ConditionalOnMissingBean} in
 * {@code LicenseAutoConfiguration}, so the {@code @Primary} override
 * wins cleanly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ReplicationPersistenceIntegrationTest.DirectorySyncEntitledConfig.class)
class ReplicationPersistenceIntegrationTest {

    @TestConfiguration
    static class DirectorySyncEntitledConfig {
        @Bean
        @Primary
        LicenseProvider directorySyncEntitledLicenseProvider() {
            // COMMUNITY edition + DIRECTORY_SYNC as an add-on grants the
            // entitlement; Instant.MAX keeps the startup reporter's expiry
            // branch a no-op.
            License full = new License(
                    null, Edition.COMMUNITY,
                    EnumSet.of(Entitlement.DIRECTORY_SYNC),
                    Map.of(), Instant.EPOCH, Instant.MAX, null);
            return new LicenseProvider() {
                @Override public License current() { return full; }
                @Override public String source() { return "test (DIRECTORY_SYNC entitled)"; }
            };
        }
    }

    @Autowired private ReplicationEnqueuer        enqueuer;
    @Autowired private ReplicationEventTxOps      txOps;
    @Autowired private ReplicationLinkRepository  linkRepo;
    @Autowired private ReplicationEventRepository eventRepo;
    @Autowired private DirectoryConnectionRepository dirRepo;
    @Autowired private TransactionTemplate        txTemplate;

    private UUID sourceDirId;
    private UUID targetDirId;

    @BeforeEach
    void cleanFixtures() {
        // Order matters: events depend on links, links depend on dirs.
        // No @Transactional on the test, so each delete commits.
        eventRepo.deleteAll();
        linkRepo.deleteAll();
        dirRepo.deleteAll();

        // Persist source + target dirs in their own tx so they're
        // visible to the unit-under-test running outside any caller tx.
        txTemplate.executeWithoutResult(tx -> {
            DirectoryConnection src = dirRepo.save(buildDir("src"));
            DirectoryConnection tgt = dirRepo.save(buildDir("tgt"));
            sourceDirId = src.getId();
            targetDirId = tgt.getId();
        });
    }

    @Test
    void enqueueAdd_withAttributeMappingsConfigured_persistsEventOutsideCallerTransaction() {
        // Regression: previously this failed with a swallowed
        // LazyInitializationException, leaving zero events in the
        // database despite the source-side write succeeding.
        UUID linkId = saveLinkWithMappings();

        // Caller has no @Transactional and the enqueuer no longer
        // opens one for the lookup — exactly the production hot path.
        enqueuer.enqueue(sourceDirId, CapturedWrite.add(
                "uid=alice,ou=people,dc=src,dc=com",
                List.of(new Attribute("uid", "alice"),
                        new Attribute("mail", "alice@src.com"))));

        List<ReplicationEvent> events = eventRepo.findAll();
        assertThat(events).hasSize(1);
        ReplicationEvent ev = events.get(0);
        assertThat(ev.getLink().getId()).isEqualTo(linkId);
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.PENDING);
        // The mapping rule (mail → email, prefixed) must have applied;
        // if attributeMappings was unreadable the enqueuer would have
        // either failed silently or stored unmapped attributes.
        @SuppressWarnings("unchecked")
        java.util.Map<String, java.util.List<String>> attrs =
                (java.util.Map<String, java.util.List<String>>) ev.getPayload().get("attributes");
        assertThat(attrs).containsKey("email");
        assertThat(attrs.get("email")).containsExactly("alice@src.com");
    }

    @Test
    void enqueueModify_withAttributeMappingsConfigured_persistsEvent() {
        // Same regression as the ADD case, different operation path
        // — mappedModifications also iterates link.attributeMappings.
        saveLinkWithMappings();

        enqueuer.enqueue(sourceDirId, CapturedWrite.modify(
                "uid=alice,ou=people,dc=src,dc=com",
                List.of(new Modification(ModificationType.REPLACE, "mail",
                        "newmail@src.com"))));

        List<ReplicationEvent> events = eventRepo.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPayload()).containsKey("modifications");
    }

    @Test
    void resetStaleInFlight_keysOnClaimTime_notEnqueuedTime() {
        // Regression: previously keyed on enqueued_at, so an event
        // enqueued > 10min ago would be false-reset the instant a
        // worker claimed it — opening a double-delivery race.
        UUID linkId = saveLinkWithMappings();
        OffsetDateTime now = OffsetDateTime.now();

        // Pre-claim an event with old enqueued_at but FRESH claim time.
        UUID eventId = insertEventDirect(linkId, now.minusHours(2));
        int claimed = txOps.tryClaim(eventId, now);
        assertThat(claimed).isEqualTo(1);

        // Stale-reset sweep using the standard 10-minute threshold.
        int reset = txOps.resetStaleInFlight(now.minus(Duration.ofMinutes(10)));

        // The freshly-claimed event must NOT be reset, even though it
        // was enqueued 2 hours ago. Under the old enqueued_at predicate
        // this would return 1 → IN_FLIGHT row flipped back to PENDING
        // mid-delivery → second worker re-claims → double apply.
        assertThat(reset).isZero();
        ReplicationEvent ev = eventRepo.findById(eventId).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.IN_FLIGHT);
    }

    @Test
    void resetStaleInFlight_resetsRowsActuallyOlderThanThreshold() {
        // Positive case for the same sweep: a row claimed 15 minutes
        // ago (older than the 10-minute threshold) IS reset to PENDING.
        UUID linkId = saveLinkWithMappings();
        OffsetDateTime now = OffsetDateTime.now();
        UUID eventId = insertEventDirect(linkId, now.minusMinutes(20));

        // Claim with an old claim time (simulating a worker that
        // crashed 15 minutes ago after claiming).
        int claimed = txOps.tryClaim(eventId, now.minusMinutes(15));
        assertThat(claimed).isEqualTo(1);

        int reset = txOps.resetStaleInFlight(now.minus(Duration.ofMinutes(10)));

        assertThat(reset).isEqualTo(1);
        ReplicationEvent ev = eventRepo.findById(eventId).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.PENDING);
        assertThat(ev.getClaimedAt()).isNull();
    }

    @Test
    void markDelivered_afterClaimRevoked_returnsFalseAndDoesNotOverwrite() {
        // Regression: markDelivered used to be an unguarded UPDATE,
        // so an in-flight worker's settlement would silently overwrite
        // a row another owner (operator-retry, stale-reset) had
        // already revoked. The new IN_FLIGHT guard turns the
        // settlement into a no-op when the claim is gone.
        UUID linkId = saveLinkWithMappings();
        OffsetDateTime now = OffsetDateTime.now();
        UUID eventId = insertEventDirect(linkId, now);
        txOps.tryClaim(eventId, now);

        // Simulate the claim being revoked between deliverAndSettle
        // and the settlement call.
        txTemplate.executeWithoutResult(tx ->
                eventRepo.findById(eventId).ifPresent(e -> {
                    e.setStatus(ReplicationEventStatus.PENDING);
                    e.setClaimedAt(null);
                    eventRepo.save(e);
                }));

        boolean settled = txOps.markDelivered(eventId);
        assertThat(settled).isFalse();
        ReplicationEvent ev = eventRepo.findById(eventId).orElseThrow();
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.PENDING);
        assertThat(ev.getDeliveredAt()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Persist a link with one attribute-mapping rule (mail→email)
     * inside a committed tx. The returned link id is then accessed
     * outside any tx by the test body, so the LazyInit access
     * pattern is the production one.
     */
    private UUID saveLinkWithMappings() {
        return txTemplate.execute(tx -> {
            ReplicationLink link = new ReplicationLink();
            link.setDisplayName("test-link");
            link.setSourceDirectory(dirRepo.findById(sourceDirId).orElseThrow());
            link.setTargetDirectory(dirRepo.findById(targetDirId).orElseThrow());
            link.setSourceBaseDn(null);
            link.setTargetBaseDn(null);
            link.setEnabled(true);

            ReplicationLinkAttrMapping rule = new ReplicationLinkAttrMapping();
            rule.setLink(link);
            rule.setSourceAttr("mail");
            rule.setTargetAttr("email");
            rule.setValueTemplate(null);
            link.getAttributeMappings().add(rule);

            return linkRepo.save(link).getId();
        });
    }

    private UUID insertEventDirect(UUID linkId, OffsetDateTime enqueuedAt) {
        return txTemplate.execute(tx -> {
            ReplicationEvent e = new ReplicationEvent();
            e.setLink(linkRepo.findById(linkId).orElseThrow());
            e.setOperation(com.ldapportal.entity.enums.ReplicationOperationType.MODIFY);
            e.setSourceDn("uid=alice,dc=src,dc=com");
            e.setTargetDn("uid=alice,dc=tgt,dc=com");
            e.setStatus(ReplicationEventStatus.PENDING);
            e.setPayload(java.util.Map.of("modifications", java.util.List.of()));
            ReplicationEvent saved = eventRepo.save(e);
            // Force the enqueuedAt past — CreationTimestamp fires on
            // insert so we have to overwrite it for staleness scenarios.
            saved.setEnqueuedAt(enqueuedAt);
            return eventRepo.save(saved).getId();
        });
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
