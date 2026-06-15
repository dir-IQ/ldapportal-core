// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;
import com.ldapportal.service.EncryptionService;
import com.ldapportal.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Delivers an outbox envelope as an HTTP POST to the subscription's webhook URL.
 * Authenticates with Bearer or HMAC per subscription config; both modes sign
 * from encrypted ciphertext decrypted only at signing time.
 *
 * <p>Failure classification:
 * <ul>
 *   <li>2xx → SUCCESS</li>
 *   <li>5xx, 408, 429 → TRANSIENT (retry)</li>
 *   <li>Other 4xx, 3xx → PERMANENT (bad config, won't self-heal)</li>
 *   <li>Network error → TRANSIENT</li>
 *   <li>Malformed destination_config → PERMANENT</li>
 * </ul>
 * </p>
 */
@Component
@Slf4j
public class WebhookChannel implements OutboundChannel {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(10);

    private final RestClient.Builder restClientBuilder;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookChannel(RestClient.Builder restClientBuilder,
                          EncryptionService encryptionService,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.restClientBuilder = restClientBuilder;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ChannelType type() { return ChannelType.WEBHOOK; }

    @Override
    public DeliveryOutcome deliver(OutboxEntrySnapshot row, EventSubscriptionSnapshot sub) {
        Map<String, Object> cfg = sub.destinationConfig();
        if (cfg == null) {
            return DeliveryOutcome.permanentFailure("null destination_config", 0);
        }
        Object urlObj = cfg.get("url");
        if (!(urlObj instanceof String url) || url.isBlank()) {
            return DeliveryOutcome.permanentFailure("missing/invalid 'url' in destination_config", 0);
        }
        // Re-validate at delivery time, not just at save time: stops a stored
        // config (or one written around the API) from being used as an SSRF
        // probe against link-local / loopback / metadata endpoints, and closes
        // the POST /event-subscriptions/{id}/test status+latency oracle.
        try {
            assertSafeDestination(url);
        } catch (IllegalArgumentException blocked) {
            return DeliveryOutcome.permanentFailure(
                    "blocked destination URL: " + blocked.getMessage(), 0);
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(row.envelope());
        } catch (JsonProcessingException e) {
            return DeliveryOutcome.permanentFailure(
                    "envelope serialization failed: " + e.getMessage(), 0);
        }

        long timestamp = clock.instant().getEpochSecond();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        rf.setReadTimeout((int) READ_TIMEOUT.toMillis());
        RestClient client = restClientBuilder.requestFactory(rf).build();

        try {
            URI target = URI.create(url);

            RestClient.RequestBodySpec req = client.post()
                    .uri(target)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LDAPPortal-Webhook/1")
                    .header("X-LDAPPortal-Event-Id",   row.eventId().toString())
                    .header("X-LDAPPortal-Event-Type", row.eventType())
                    .header("X-LDAPPortal-Timestamp",  Long.toString(timestamp));

            try {
                applyAuth(req, cfg, timestamp, body);
            } catch (IllegalStateException authEx) {
                return DeliveryOutcome.permanentFailure(authEx.getMessage(), 0);
            }

            int status = req.body(body).retrieve().toBodilessEntity().getStatusCode().value();
            if (status >= 200 && status < 300) {
                return DeliveryOutcome.success(status);
            }
            return classify(status, status + " non-success response");

        } catch (HttpStatusCodeException httpEx) {
            int status = httpEx.getStatusCode().value();
            String reason = status + " " + httpEx.getStatusText();
            return classify(status, reason);

        } catch (ResourceAccessException netEx) {
            return DeliveryOutcome.transientFailure("network: " + netEx.getMessage(), null);

        } catch (IllegalArgumentException illegalArg) {
            // URI.create throws IllegalArgumentException for malformed URLs
            return DeliveryOutcome.permanentFailure(
                    "malformed URL: " + illegalArg.getMessage(), 0);

        } catch (RuntimeException e) {
            return DeliveryOutcome.transientFailure(
                    "unexpected: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    /**
     * SSRF guard, isolated as a public seam so tests that must deliver to a
     * loopback {@code MockWebServer} (which the real guard rightly blocks) can
     * neutralise it — via an override (WebhookChannelTest) or a Mockito spy
     * (EventBackboneEndToEndTest). Production always uses
     * {@link UrlValidator#requireSafeUrl}.
     */
    public void assertSafeDestination(String url) {
        UrlValidator.requireSafeUrl(url);
    }

    private static DeliveryOutcome classify(int status, String message) {
        if (status >= 500) return DeliveryOutcome.transientFailure(message, status);
        if (status == 408 || status == 429) return DeliveryOutcome.transientFailure(message, status);
        return DeliveryOutcome.permanentFailure(message, status);
    }

    private void applyAuth(RestClient.RequestBodySpec req,
                           Map<String, Object> cfg, long timestamp, String body) {
        Object authObj = cfg.get("auth");
        if (!(authObj instanceof Map<?, ?> authMap)) return;

        Object typeObj = authMap.get("type");
        if (!(typeObj instanceof String authType)) {
            throw new IllegalStateException("auth.type missing or non-string");
        }
        switch (authType) {
            case "bearer" -> {
                Object enc = authMap.get("tokenEnc");
                if (!(enc instanceof String tokenEnc) || tokenEnc.isBlank()) {
                    throw new IllegalStateException("bearer auth requires non-blank tokenEnc");
                }
                String token = encryptionService.decrypt(tokenEnc);
                req.header("Authorization", "Bearer " + token);
            }
            case "hmac" -> {
                Object enc = authMap.get("secretEnc");
                if (!(enc instanceof String secretEnc) || secretEnc.isBlank()) {
                    throw new IllegalStateException("hmac auth requires non-blank secretEnc");
                }
                String secret = encryptionService.decrypt(secretEnc);
                String sig = hmacSha256Hex(secret, timestamp + "." + body);
                req.header("X-LDAPPortal-Signature", "sha256=" + sig);
            }
            default -> throw new IllegalStateException("unknown webhook auth type: " + authType);
        }
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
