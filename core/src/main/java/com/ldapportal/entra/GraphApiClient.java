// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.DirectoryConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Low-level HTTP client for Microsoft Graph API.
 * Handles authentication, pagination, and rate limiting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphApiClient {

    private final EntraTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final HttpClient entraHttpClient;

    private static final int MAX_PAGES = 100;
    private static final int MAX_RETRIES = 3;

    /**
     * Execute a GET request against the Graph API.
     *
     * @param dc          the Entra ID directory connection
     * @param path        API path (e.g., "/v1.0/users")
     * @param queryParams optional query parameters
     * @return parsed JSON response
     */
    public JsonNode get(DirectoryConnection dc, String path, Map<String, String> queryParams) {
        String baseUrl = resolveEndpoint(dc);
        String url = baseUrl + path;

        if (queryParams != null && !queryParams.isEmpty()) {
            // OData query keys ($filter, $select, $top, etc.) are passed
            // unencoded — '$' is reserved in RFC 3986 but Graph accepts it
            // verbatim and that's the canonical OData form. VALUES must be
            // percent-encoded: filter expressions contain spaces ('ge ') and
            // colons (in date-times) that URI.create() rejects with "Invalid
            // URL" otherwise. Symptom before this fix: scheduled audit-log
            // sync logged a recurring "Invalid URL: ...?$filter=activityDateTime
            // ge 2026-04-26T..." every poll cycle.
            String qs = queryParams.entrySet().stream()
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            url += "?" + qs;
        }

        return executeWithRetry(dc, url);
    }

    /**
     * GET a single page by full URL (used for @odata.nextLink pagination).
     */
    public JsonNode getByUrl(DirectoryConnection dc, String fullUrl) {
        return executeWithRetry(dc, fullUrl);
    }

    /**
     * Fetch all pages for a collection endpoint. Follows @odata.nextLink
     * until exhausted or MAX_PAGES reached.
     *
     * @return list of all "value" array elements across all pages
     */
    public List<JsonNode> getAllPages(DirectoryConnection dc, String path, Map<String, String> queryParams) {
        List<JsonNode> results = new ArrayList<>();
        JsonNode page = get(dc, path, queryParams);
        int pageCount = 0;

        while (page != null && pageCount < MAX_PAGES) {
            JsonNode value = page.get("value");
            if (value != null && value.isArray()) {
                for (JsonNode item : value) {
                    results.add(item);
                }
            }

            // Follow pagination
            JsonNode nextLink = page.get("@odata.nextLink");
            if (nextLink != null && !nextLink.isNull()) {
                page = getByUrl(dc, nextLink.asText());
                pageCount++;
            } else {
                break;
            }
        }

        if (pageCount >= MAX_PAGES) {
            log.warn("Graph API pagination hit MAX_PAGES ({}) for path {}", MAX_PAGES, path);
        }

        return results;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private JsonNode executeWithRetry(DirectoryConnection dc, String url) {
        // Defense-in-depth: re-validate the full URL right before the HTTP send.
        // resolveEndpoint() already validates the base, but `url` here is the
        // concatenated final form (base + path + query) for forward calls, OR a
        // server-supplied @odata.nextLink for pagination. The pagination case is
        // the one that motivates this: nextLink comes from the Graph response
        // body and could (in a TLS-compromise / response-poisoning scenario) be
        // pointed at an attacker-controlled host. requireSafeUrl blocks loopback,
        // link-local, site-local (RFC1918), and the cloud metadata IP.
        com.ldapportal.util.UrlValidator.requireSafeUrl(url);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String token = tokenService.getAccessToken(dc);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json")
                        .header("ConsistencyLevel", "eventual")
                        .GET()
                        .build();

                HttpResponse<String> response = entraHttpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readTree(response.body());
                }

                // Rate limited — respect Retry-After header
                if (response.statusCode() == 429) {
                    int retryAfter = parseRetryAfter(response);
                    log.warn("Graph API rate limited (429). Retry-After: {}s (attempt {}/{})",
                            retryAfter, attempt, MAX_RETRIES);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(retryAfter * 1000L);
                        continue;
                    }
                }

                // Auth failure — evict token and retry once
                if (response.statusCode() == 401 && attempt == 1) {
                    log.debug("Graph API 401 — evicting token and retrying");
                    tokenService.evict(dc.getId());
                    continue;
                }

                throw new EntraApiException("Graph API error (HTTP " + response.statusCode() + "): "
                        + truncate(response.body(), 500));

            } catch (EntraApiException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EntraApiException("Graph API call interrupted", e);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    throw new EntraApiException("Graph API call failed after " + MAX_RETRIES + " attempts: "
                            + e.getMessage(), e);
                }
                log.debug("Graph API call failed (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
            }
        }
        throw new EntraApiException("Graph API call failed after " + MAX_RETRIES + " attempts");
    }

    private int parseRetryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try { return Integer.parseInt(v); }
                    catch (NumberFormatException e) { return 10; }
                })
                .orElse(10);
    }

    private String resolveEndpoint(DirectoryConnection dc) {
        String endpoint = dc.getGraphEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "https://graph.microsoft.com";
        }
        com.ldapportal.util.UrlValidator.requireSafeUrl(endpoint);
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
