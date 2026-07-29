// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IsvaConfigExportContributorTest {

    private final IsvaConfigService isvaConfigService = mock(IsvaConfigService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final IsvaConfigExportContributor contributor =
            new IsvaConfigExportContributor(isvaConfigService, objectMapper);

    @Test
    void export_addsIsvaSection_inReconcilerShape() {
        UpsertIsvaConfigRequest cfg = new UpsertIsvaConfigRequest(
                true, IsvaTopologyMode.INLINE, "Default", 100,
                IsvaDeletePolicy.DISABLE, true, List.of("secUser"),
                null, null, null, null, null);   // linked-mode-only fields unset
        when(isvaConfigService.exportAll()).thenReturn(List.of(
                new IsvaConfigService.IsvaConfigExport("corp-ldap", cfg)));

        Map<String, Object> root = new LinkedHashMap<>();
        contributor.export(root);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> isva = (List<Map<String, Object>>) root.get("isva");
        assertThat(isva).hasSize(1);
        assertThat(isva.get(0).get("directorySlug")).isEqualTo("corp-ldap");

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) isva.get(0).get("config");
        assertThat(config.get("topologyMode")).isEqualTo("INLINE");
        assertThat(config.get("deletePolicy")).isEqualTo("DISABLE");
        assertThat(config.get("secAuthority")).isEqualTo("Default");
        assertThat(config).doesNotContainKey("managementDitBaseDn");   // null dropped

        // Round-trip back into the request the reconciler applies.
        UpsertIsvaConfigRequest back = objectMapper.convertValue(config, UpsertIsvaConfigRequest.class);
        assertThat(back.topologyMode()).isEqualTo(IsvaTopologyMode.INLINE);
        assertThat(back.enabled()).isTrue();
    }

    @Test
    void export_noConfigs_addsNothing() {
        when(isvaConfigService.exportAll()).thenReturn(List.of());
        Map<String, Object> root = new LinkedHashMap<>();
        contributor.export(root);
        assertThat(root).doesNotContainKey("isva");
    }
}
