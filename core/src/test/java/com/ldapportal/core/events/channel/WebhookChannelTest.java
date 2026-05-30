// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;
import com.ldapportal.service.EncryptionService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookChannelTest {

    @Mock private EncryptionService encryptionService;

    private MockWebServer server;
    private WebhookChannel channel;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // MockWebServer binds to loopback, which the real SSRF guard blocks.
        // These tests exercise HTTP status classification, not the guard, so
        // neutralise the seam here; the guard itself is covered separately
        // (blockedDestination_* below and UrlValidatorTest).
        channel = new WebhookChannel(RestClient.builder(), encryptionService, new ObjectMapper(), fixedClock) {
            @Override
            public void assertSafeDestination(String url) { /* allow loopback in tests */ }
        };
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void http200_returnsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        DeliveryOutcome outcome = channel.deliver(row(), subNoAuth());
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.SUCCESS);
        assertThat(outcome.httpStatus()).isEqualTo(200);

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(req.getHeader("User-Agent")).isEqualTo("LDAPPortal-Webhook/1");
        assertThat(req.getHeader("X-LDAPPortal-Event-Id")).isNotBlank();
        assertThat(req.getHeader("X-LDAPPortal-Event-Type")).isEqualTo("api_token.created");
        assertThat(req.getHeader("X-LDAPPortal-Timestamp")).isNotBlank();
    }

    @Test
    void http500_returnsTransient() {
        server.enqueue(new MockResponse().setResponseCode(500));
        DeliveryOutcome outcome = channel.deliver(row(), subNoAuth());
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(outcome.httpStatus()).isEqualTo(500);
    }

    @Test
    void http429_returnsTransient() {
        server.enqueue(new MockResponse().setResponseCode(429));
        assertThat(channel.deliver(row(), subNoAuth()).kind())
                .isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
    }

    @Test
    void http408_returnsTransient() {
        server.enqueue(new MockResponse().setResponseCode(408));
        assertThat(channel.deliver(row(), subNoAuth()).kind())
                .isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
    }

    @Test
    void http401_returnsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(401));
        DeliveryOutcome outcome = channel.deliver(row(), subNoAuth());
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.httpStatus()).isEqualTo(401);
    }

    @Test
    void http404_returnsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(channel.deliver(row(), subNoAuth()).kind())
                .isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
    }

    @Test
    void http302_returnsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://other"));
        assertThat(channel.deliver(row(), subNoAuth()).kind())
                .isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
    }

    @Test
    void connectionFailure_returnsTransient() throws Exception {
        // Shut down immediately so the channel hits a connection refused.
        int port = server.getPort();
        server.shutdown();
        EventSubscriptionSnapshot sub = subscriptionTo("http://127.0.0.1:" + port + "/");
        DeliveryOutcome outcome = channel.deliver(row(), sub);
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(outcome.httpStatus()).isNull();
    }

    @Test
    void bearerAuth_setsAuthorizationHeader() throws Exception {
        when(encryptionService.decrypt("ENC-TOKEN")).thenReturn("plain-token");
        server.enqueue(new MockResponse().setResponseCode(200));

        EventSubscriptionSnapshot sub = subscriptionWithConfig(Map.of(
                "url", server.url("/").toString(),
                "auth", Map.of("type", "bearer", "tokenEnc", "ENC-TOKEN")));

        channel.deliver(row(), sub);

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer plain-token");
        assertThat(req.getHeader("X-LDAPPortal-Signature")).isNull();
    }

    @Test
    void hmacAuth_setsVerifiableSignature() throws Exception {
        String secret = "shared-hmac-secret";
        when(encryptionService.decrypt("ENC-SECRET")).thenReturn(secret);
        server.enqueue(new MockResponse().setResponseCode(200));

        EventSubscriptionSnapshot sub = subscriptionWithConfig(Map.of(
                "url", server.url("/").toString(),
                "auth", Map.of("type", "hmac", "secretEnc", "ENC-SECRET")));

        OutboxEntrySnapshot row = row();
        channel.deliver(row, sub);

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        String timestamp = req.getHeader("X-LDAPPortal-Timestamp");
        String body = req.getBody().readUtf8();
        String sigHeader = req.getHeader("X-LDAPPortal-Signature");

        assertThat(sigHeader).startsWith("sha256=");
        String expected = "sha256=" + hmacSha256Hex(secret, timestamp + "." + body);
        assertThat(sigHeader).isEqualTo(expected);
    }

    @Test
    void noAuth_sendsNoAuthHeaders() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        channel.deliver(row(), subNoAuth());
        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req.getHeader("Authorization")).isNull();
        assertThat(req.getHeader("X-LDAPPortal-Signature")).isNull();
    }

    @Test
    void malformedConfig_returnsPermanent() {
        EventSubscriptionSnapshot sub = subscriptionWithConfig(
                Map.of("not-url-field", "garbage"));
        DeliveryOutcome outcome = channel.deliver(row(), sub);
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
    }

    @Test
    void blockedDestination_returnsPermanent_andMakesNoRequest() {
        // SSRF guard: a config pointing at the cloud metadata endpoint must be
        // rejected at delivery time without any outbound HTTP request firing.
        // Uses the production guard (not the neutralised seam above).
        WebhookChannel guarded =
                new WebhookChannel(RestClient.builder(), encryptionService, new ObjectMapper(), fixedClock);
        EventSubscriptionSnapshot sub = subscriptionTo("http://169.254.169.254/latest/meta-data/");
        DeliveryOutcome outcome = guarded.deliver(row(), sub);

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.message()).contains("blocked destination URL");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void deliveredEnvelopeIsJsonOfEntryEnvelope() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        OutboxEntrySnapshot row = row();
        channel.deliver(row, subNoAuth());

        RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
        String body = req.getBody().readUtf8();
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> parsed = mapper.readValue(body, Map.class);
        assertThat(parsed.get("type")).isEqualTo("api_token.created");
        assertThat(parsed.get("schemaVersion")).isEqualTo(1);
    }

    // ── helpers ──

    private OutboxEntrySnapshot row() {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", eventId.toString());
        env.put("type", "api_token.created");
        env.put("schemaVersion", 1);
        env.put("occurredAt", "2026-04-23T10:00:00Z");
        env.put("actor", Map.of("id", "u-1", "username", "alice", "type", "SUPERADMIN"));
        env.put("resource", Map.of("type", "api_token", "id", ""));
        env.put("payload", Map.of("tokenId", "t-1", "tokenName", "ci"));
        return new OutboxEntrySnapshot(
                UUID.randomUUID(), UUID.randomUUID(), eventId, "api_token.created",
                Instant.parse("2026-04-23T10:00:00Z"), env,
                OutboxStatus.DELIVERING, 1, Instant.parse("2026-04-23T10:00:00Z"),
                null, null, null, null, Instant.parse("2026-04-23T10:00:00Z"));
    }

    private EventSubscriptionSnapshot subNoAuth() {
        return subscriptionTo(server.url("/").toString());
    }

    private EventSubscriptionSnapshot subscriptionTo(String url) {
        return subscriptionWithConfig(Map.of("url", url));
    }

    private EventSubscriptionSnapshot subscriptionWithConfig(Map<String, Object> cfg) {
        return new EventSubscriptionSnapshot(
                UUID.randomUUID(), "test", null, ChannelType.WEBHOOK,
                cfg, null, true, null);
    }

    private static String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
