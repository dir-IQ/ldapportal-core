// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.0 client credentials flow for Microsoft Entra ID.
 * Tokens are cached per directory and auto-refreshed before expiry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntraTokenService {

    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final HttpClient entraHttpClient;

    private final Map<UUID, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private static final String TOKEN_URL_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String DEFAULT_SCOPE = "https://graph.microsoft.com/.default";

    /**
     * Get a valid access token for the given Entra ID directory connection.
     * Returns a cached token if still valid, otherwise acquires a new one.
     */
    public String getAccessToken(DirectoryConnection dc) {
        CachedToken cached = tokenCache.get(dc.getId());
        if (cached != null && cached.isValid()) {
            return cached.token;
        }

        return tokenCache.compute(dc.getId(), (id, existing) -> {
            // Double-check inside compute — another thread may have refreshed
            if (existing != null && existing.isValid()) return existing;
            return acquireTokenCached(dc);
        }).token;
    }

    /** Evict cached token for a directory (e.g., after secret rotation). */
    public void evict(UUID directoryId) {
        tokenCache.remove(directoryId);
    }

    private CachedToken acquireTokenCached(DirectoryConnection dc) {
        String tenantId = dc.getTenantId();
        String clientId = dc.getEntraClientId();
        String clientSecret = encryptionService.decrypt(dc.getEntraClientSecretEncrypted());

        String url = String.format(TOKEN_URL_TEMPLATE, tenantId);
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&scope=" + encode(DEFAULT_SCOPE)
                + "&grant_type=client_credentials";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = entraHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EntraApiException("Token request failed (HTTP " + response.statusCode() + "): "
                        + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String token = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;

            log.debug("Acquired Entra ID token for tenant {} (expires in {}s)", tenantId, expiresIn);
            return new CachedToken(token, Instant.now().plusSeconds(expiresIn - 300));

        } catch (EntraApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EntraApiException("Failed to acquire Entra ID token: " + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
