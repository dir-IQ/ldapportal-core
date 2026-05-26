// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.DirectoryConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphApiClientTest {

    @Mock private EntraTokenService tokenService;
    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private GraphApiClient graphClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        graphClient = new GraphApiClient(tokenService, objectMapper, httpClient);
    }

    private DirectoryConnection makeConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setGraphEndpoint("https://graph.microsoft.com");
        return dc;
    }

    private void stubHttp(int status, String... bodies) throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        if (bodies.length == 1) {
            when(httpResponse.statusCode()).thenReturn(status);
            when(httpResponse.body()).thenReturn(bodies[0]);
        }
    }

    @Test
    void get_returnsJsonResponse() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(tokenService.getAccessToken(dc)).thenReturn("token123");
        stubHttp(200, "{\"id\":\"abc\",\"displayName\":\"Test\"}");

        JsonNode result = graphClient.get(dc, "/v1.0/users/abc", Map.of("$select", "id,displayName"));

        assertThat(result.get("id").asText()).isEqualTo("abc");
        assertThat(result.get("displayName").asText()).isEqualTo("Test");
    }

    @Test
    void get_throwsOnServerError() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(tokenService.getAccessToken(dc)).thenReturn("token123");
        stubHttp(500, "{\"error\":\"InternalServerError\"}");

        assertThatThrownBy(() -> graphClient.get(dc, "/v1.0/users", Map.of()))
                .isInstanceOf(EntraApiException.class)
                .hasMessageContaining("500");
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_retriesOn401AndEvictsToken() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(tokenService.getAccessToken(dc)).thenReturn("expired-token", "new-token");

        HttpResponse<String> resp401 = mock(HttpResponse.class);
        lenient().when(resp401.statusCode()).thenReturn(401);
        lenient().when(resp401.body()).thenReturn("{\"error\":\"Unauthorized\"}");

        HttpResponse<String> resp200 = mock(HttpResponse.class);
        lenient().when(resp200.statusCode()).thenReturn(200);
        lenient().when(resp200.body()).thenReturn("{\"value\":[]}");

        doReturn(resp401).doReturn(resp200).when(httpClient).send(any(), any());

        JsonNode result = graphClient.get(dc, "/v1.0/users", Map.of());
        assertThat(result).isNotNull();
        verify(tokenService).evict(dc.getId());
    }

    @Test
    void getAllPages_followsNextLink() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(tokenService.getAccessToken(dc)).thenReturn("token123");
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                "{\"value\":[{\"id\":\"1\"}],\"@odata.nextLink\":\"https://graph.microsoft.com/v1.0/users?$skiptoken=abc\"}",
                "{\"value\":[{\"id\":\"2\"}]}"
        );

        List<JsonNode> results = graphClient.getAllPages(dc, "/v1.0/users", Map.of("$top", "1"));
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("id").asText()).isEqualTo("1");
        assertThat(results.get(1).get("id").asText()).isEqualTo("2");
    }

    @Test
    void get_usesCustomEndpoint() throws Exception {
        DirectoryConnection dc = makeConnection();
        dc.setGraphEndpoint("https://graph.microsoft.us");
        when(tokenService.getAccessToken(dc)).thenReturn("token123");
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"value\":[]}");

        graphClient.get(dc, "/v1.0/organization", Map.of());

        verify(httpClient).send(argThat(req ->
                req.uri().toString().startsWith("https://graph.microsoft.us")), any());
    }

    /**
     * Regression: the audit-log scheduler builds an OData $filter expression
     * containing spaces and colons (e.g. "activityDateTime ge 2026-04-26T...").
     * Without URL-encoding, URI.create() rejects the URL and the scheduler
     * logs "Invalid URL: ..." every poll cycle. Verify spaces percent-encode.
     */
    @Test
    void get_percentEncodesQueryValuesWithSpaces() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(tokenService.getAccessToken(dc)).thenReturn("token123");
        stubHttp(200, "{\"value\":[]}");

        graphClient.get(dc, "/v1.0/auditLogs/directoryAudits",
                Map.of("$filter", "activityDateTime ge 2026-04-26T06:43:50Z"));

        verify(httpClient).send(argThat(req -> {
            String url = req.uri().toString();
            return url.contains("$filter=activityDateTime")
                && !url.contains("activityDateTime ge ")     // raw space is the bug
                && url.contains("ge+2026-04-26T06%3A43%3A50Z"); // form-urlencoded value
        }), any());
    }
}
