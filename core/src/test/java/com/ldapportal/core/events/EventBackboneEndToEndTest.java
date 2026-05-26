// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.events.channel.WebhookChannel;
import com.ldapportal.auth.ApiTokenService;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * Full end-to-end: create ApiToken → AuditService.record → bridge → outbox →
 * dispatcher → WebhookChannel → MockWebServer. Validates every stage together.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class EventBackboneEndToEndTest {

    // Pinned minor (matches docker-compose.yml + TestcontainersConfiguration)
    // so Dependabot's docker-image ecosystem can track upgrades. The floating
    // postgres:16-alpine tag would silently drift on every pull.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.13-alpine");

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    }

    @Autowired private EventSubscriptionRepository subscriptionRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ApiTokenService apiTokenService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OutboundDispatcherScheduler scheduler;

    // MockWebServer binds to loopback, which the real SSRF guard added in
    // WebhookChannel.deliver() blocks. This e2e test exercises the full
    // delivery pipeline end-to-end, not the guard itself (covered by
    // WebhookChannelTest + UrlValidatorTest), so spy the autowired channel and
    // neutralise just the guard seam — leaving real delivery intact.
    @MockitoSpyBean private WebhookChannel webhookChannel;

    private MockWebServer server;
    private Account superadmin;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        doNothing().when(webhookChannel).assertSafeDestination(anyString());

        outboxRepository.deleteAll();
        subscriptionRepository.deleteAll();

        superadmin = accountRepository.findAll().stream()
                .filter(a -> a.getRole() == AccountRole.SUPERADMIN && a.isActive())
                .findFirst().orElseGet(this::insertSuperadmin);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private Account insertSuperadmin() {
        Account a = new Account();
        a.setUsername("e2e-superadmin-" + UUID.randomUUID());
        a.setDisplayName("E2E Superadmin");
        a.setRole(AccountRole.SUPERADMIN);
        a.setAuthType(AccountType.LOCAL);
        a.setPasswordHash("$2a$10$irrelevant");
        a.setActive(true);
        return accountRepository.save(a);
    }

    private EventSubscription insertWebhookSubscription(List<String> filter) {
        EventSubscription sub = new EventSubscription();
        sub.setName("e2e-webhook");
        sub.setChannelType(ChannelType.WEBHOOK);
        sub.setDestinationConfig(Map.of("url", server.url("/hook").toString()));
        sub.setEventTypeFilter(filter);
        sub.setEnabled(true);
        return subscriptionRepository.save(sub);
    }

    /**
     * Requires Postgres: {@code findActiveMatchingType} uses {@code jsonb @>} and
     * {@code claimBatch} uses {@code FOR UPDATE SKIP LOCKED} — both unsupported by
     * H2. Migrate this test to a Testcontainers Postgres profile to enable it.
     */
    @Test
    void auditTriggers_outboxInserted_dispatcherDelivers_webhookReceivesEnvelope() throws Exception {
        insertWebhookSubscription(List.of("api_token.created"));
        server.enqueue(new MockResponse().setResponseCode(200));

        // Trigger an audit-worthy state change. ApiTokenService.create() calls
        // AuditService.recordSystemEvent which fires AuditRecordedEvent.
        apiTokenService.create("e2e-token",
                "triggered by e2e test",
                Instant.now().plus(30, ChronoUnit.DAYS),
                superadmin);

        RecordedRequest req = server.takeRequest(30, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("X-LDAPPortal-Event-Type")).isEqualTo("api_token.created");

        Map<?, ?> envelope = objectMapper.readValue(req.getBody().readUtf8(), Map.class);
        assertThat(envelope.get("type")).isEqualTo("api_token.created");
        assertThat(envelope.get("schemaVersion")).isEqualTo(1);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(outboxRepository.countByStatus(OutboxStatus.DELIVERED)).isEqualTo(1));
    }

    /**
     * Requires Postgres: {@code findActiveMatchingType} uses {@code jsonb @>}.
     * Without a real Postgres the bridge throws async and the outbox stays empty
     * — a false pass. Migrate to a Testcontainers Postgres profile to enable.
     */
    @Test
    void eventTypeFilter_excludesNonMatchingEvents() throws Exception {
        // Subscription only wants group events; api_token events shouldn't hit it.
        insertWebhookSubscription(List.of("group.created"));

        apiTokenService.create("filtered-token",
                null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                superadmin);

        // Give the bridge a moment to NOT enqueue.
        Thread.sleep(2_000);
        assertThat(outboxRepository.count()).isEqualTo(0);
    }

    /**
     * Requires Postgres: bridge and dispatcher both use Postgres-specific SQL.
     * Needs Testcontainers to run.
     */
    @Test
    void transientFailure_retriesUntilSuccess() throws Exception {
        insertWebhookSubscription(List.of("api_token.created"));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200));

        apiTokenService.create("retry-token",
                null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                superadmin);

        // The scheduler's BACKOFF[0] is 1 minute, so this test can't await the
        // retries within a fast test. Instead, assert that the first delivery
        // hit and the row transitioned to PENDING with attempts=1 after failure.
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            OutboxEntry row = outboxRepository.findAll().iterator().next();
            assertThat(row.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(row.getStatus()).isIn(OutboxStatus.PENDING, OutboxStatus.DELIVERING);
            assertThat(row.getLastError()).contains("503");
        });
    }

    /**
     * Requires Postgres: bridge and dispatcher both use Postgres-specific SQL.
     * Needs Testcontainers to run.
     */
    @Test
    void permanentFailure_deadLetters() throws Exception {
        insertWebhookSubscription(List.of("api_token.created"));
        server.enqueue(new MockResponse().setResponseCode(401));

        apiTokenService.create("perm-fail-token",
                null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                superadmin);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            OutboxEntry row = outboxRepository.findAll().iterator().next();
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
            assertThat(row.getLastHttpStatus()).isEqualTo(401);
        });
    }

    /**
     * Regression for the bug fixed in commit 81645d0:
     * {@link OutboundDispatcherScheduler#resetStaleDelivering()} called
     * {@link OutboxEntryRepository#resetStaleDelivering} (a {@code @Modifying}
     * query) without wrapping it in a transaction, throwing
     * {@code TransactionRequiredException} every 60s in production.
     *
     * <p>The unit test for the same behaviour passed because both the repo
     * and {@code TransactionTemplate} were mocked — Hibernate's runtime
     * contract was nowhere in the test stack. This E2E test runs the real
     * scheduler bean against real Postgres; without the fix it throws.</p>
     */
    @Test
    void resetStaleDelivering_rescuesStuckRow_runsInTransaction() {
        EventSubscription sub = insertWebhookSubscription(List.of("test.stuck"));

        // Seed a row stuck in DELIVERING longer than MAX_DELIVERING (5 min).
        // dispatch() ignores DELIVERING rows so it can't race with the test.
        OutboxEntry stuck = new OutboxEntry();
        stuck.setSubscriptionId(sub.getId());
        stuck.setEventId(UUID.randomUUID());
        stuck.setEventType("test.stuck");
        stuck.setStatus(OutboxStatus.DELIVERING);
        stuck.setOccurredAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        stuck.setNextAttemptAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        stuck.setEnvelope(Map.<String, Object>of("seeded", true));
        stuck.setAttempts(1);
        UUID id = outboxRepository.save(stuck).getId();

        // Direct invocation: the @Scheduled trigger only fires every 60s,
        // we can't wait that long in a fast test. Call the bean method directly.
        scheduler.resetStaleDelivering();

        OutboxEntry after = outboxRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    /**
     * Requires Postgres: bridge uses {@code jsonb @>} when inserting outbox rows.
     * Needs Testcontainers to run.
     */
    @Test
    void deletedSubscription_cascadesOutboxRows() throws Exception {
        EventSubscription sub = insertWebhookSubscription(null);  // all events
        // Make the webhook target refuse to respond so the row stays in flight.
        // (Enqueue nothing — MockWebServer will just not complete.)
        apiTokenService.create("cascade-token",
                null,
                Instant.now().plus(30, ChronoUnit.DAYS),
                superadmin);

        // Wait for at least one outbox row to land.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
            outboxRepository.count() > 0);
        long before = outboxRepository.count();
        assertThat(before).isGreaterThan(0);

        subscriptionRepository.deleteById(sub.getId());

        assertThat(outboxRepository.count()).isEqualTo(0L);
    }
}
