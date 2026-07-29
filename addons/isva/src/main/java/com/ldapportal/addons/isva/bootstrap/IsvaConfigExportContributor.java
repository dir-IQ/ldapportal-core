// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.core.bootstrap.ConfigExportContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contributes the {@code isva} section to the declarative config export — the
 * inverse of {@link IsvaBootstrapConfigContributor}. Registered only when the
 * ISVA addon is on the classpath, so a community build simply omits the section
 * (core never sees this code — edition boundary).
 *
 * <p>Each entry is {@code { directorySlug, config: { ... } }}, mirroring the
 * shape the bootstrap reconciler applies, so an exported dump feeds straight
 * back in. The config carries no secrets, so nothing is redacted.</p>
 */
@Component
@RequiredArgsConstructor
public class IsvaConfigExportContributor implements ConfigExportContributor {

    private final IsvaConfigService isvaConfigService;
    private final ObjectMapper objectMapper;

    @Override
    public void export(Map<String, Object> root) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (IsvaConfigService.IsvaConfigExport e : isvaConfigService.exportAll()) {
            Map<String, Object> config = objectMapper.convertValue(
                    e.config(), new TypeReference<LinkedHashMap<String, Object>>() {});
            // Drop unset (null) linked-mode-only fields to keep the YAML clean;
            // the reconciler defaults them anyway.
            config.values().removeIf(java.util.Objects::isNull);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("directorySlug", e.directorySlug());
            entry.put("config", config);
            entries.add(entry);
        }
        if (!entries.isEmpty()) {
            root.put("isva", entries);
        }
    }
}
