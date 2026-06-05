// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IsvaBootstrapConfigContributorTest {

    private final IsvaConfigService isvaConfigService = mock(IsvaConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final IsvaBootstrapConfigContributor contributor =
            new IsvaBootstrapConfigContributor(isvaConfigService, objectMapper, validator);

    private JsonNode tree(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void noIsvaSection_isNoOp() throws Exception {
        contributor.contribute(tree("{\"directories\": []}"));
        verifyNoInteractions(isvaConfigService);
    }

    @Test
    void appliesConfig_resolvingSlugToDirectoryId() throws Exception {
        UUID dirId = UUID.randomUUID();
        when(isvaConfigService.resolveDirectoryIdBySlug("corp-ldap")).thenReturn(dirId);

        contributor.contribute(tree("""
                { "isva": [ {
                  "directorySlug": "corp-ldap",
                  "config": {
                    "enabled": true, "topologyMode": "INLINE", "secAuthority": "Default",
                    "defaultValidUntilYears": 100, "deletePolicy": "DISABLE", "requireSecGroup": true
                  } } ] }
                """));

        verify(isvaConfigService).upsert(eq(dirId), any(UpsertIsvaConfigRequest.class), isNull());
    }

    @Test
    void missingDirectorySlug_failsFast() throws Exception {
        assertThatThrownBy(() -> contributor.contribute(tree("""
                { "isva": [ { "config": { "topologyMode": "INLINE" } } ] }
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("directorySlug");
        verifyNoInteractions(isvaConfigService);
    }

    @Test
    void invalidConfig_failsFast() throws Exception {
        // Empty config -> topologyMode/deletePolicy null, defaultValidUntilYears 0 -> violations.
        assertThatThrownBy(() -> contributor.contribute(tree("""
                { "isva": [ { "directorySlug": "corp-ldap", "config": {} } ] }
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Validation failed");
    }
}
