// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntraTokenServiceTest {

    @Mock private EncryptionService encryptionService;
    @Mock private HttpClient httpClient;
    @Mock private HttpResponse<String> httpResponse;

    private EntraTokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tokenService = new EntraTokenService(encryptionService, objectMapper, httpClient);
    }

    private DirectoryConnection makeConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setTenantId("test-tenant-id");
        dc.setEntraClientId("test-client-id");
        dc.setEntraClientSecretEncrypted("encrypted-secret");
        dc.setDisplayName("Test Entra");
        return dc;
    }

    private void stubHttpResponse(int status, String body) throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(status);
        when(httpResponse.body()).thenReturn(body);
    }

    @Test
    void getAccessToken_acquiresAndCachesToken() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(encryptionService.decrypt("encrypted-secret")).thenReturn("plain-secret");
        stubHttpResponse(200, "{\"access_token\":\"tok123\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");

        String token = tokenService.getAccessToken(dc);
        assertThat(token).isEqualTo("tok123");

        // Second call should return cached — no additional HTTP call
        String cached = tokenService.getAccessToken(dc);
        assertThat(cached).isEqualTo("tok123");
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    void getAccessToken_throwsOnAuthFailure() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(encryptionService.decrypt("encrypted-secret")).thenReturn("bad-secret");
        stubHttpResponse(401, "{\"error\":\"invalid_client\"}");

        assertThatThrownBy(() -> tokenService.getAccessToken(dc))
                .isInstanceOf(EntraApiException.class)
                .hasMessageContaining("Token request failed");
    }

    @Test
    void evict_removesFromCache() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(encryptionService.decrypt("encrypted-secret")).thenReturn("plain-secret");
        stubHttpResponse(200, "{\"access_token\":\"tok1\",\"expires_in\":3600}");

        tokenService.getAccessToken(dc);
        tokenService.evict(dc.getId());

        when(httpResponse.body()).thenReturn("{\"access_token\":\"tok2\",\"expires_in\":3600}");
        String token = tokenService.getAccessToken(dc);
        assertThat(token).isEqualTo("tok2");
        verify(httpClient, times(2)).send(any(), any());
    }
}
