// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.core.bootstrap.BootstrapConfigContributor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies the {@code isva} section of the declarative bootstrap config (Phase 2
 * of the IaC plan). Registered only when the ISVA addon is on the classpath, so
 * a community build simply ignores any {@code isva} section — core never sees
 * this code (edition boundary).
 *
 * <p>Each entry is {@code { directorySlug, config: { ... } }}: the slug is
 * resolved to a directory id (the directory must already exist — the core
 * reconciler applies the {@code directories} section first) and the config is
 * upserted through the same idempotent {@link IsvaConfigService} path the REST
 * API uses. A bad entry throws, aborting startup.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IsvaBootstrapConfigContributor implements BootstrapConfigContributor {

    private final IsvaConfigService isvaConfigService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Override
    public void contribute(JsonNode root) {
        JsonNode node = root.path("isva");
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw new IllegalStateException("Bootstrap config 'isva' must be a list");
        }

        int applied = 0;
        for (JsonNode entry : node) {
            String slug = entry.path("directorySlug").asText(null);
            if (slug == null || slug.isBlank()) {
                throw new IllegalStateException(
                        "Each bootstrap 'isva' entry must declare a non-blank 'directorySlug'");
            }
            UpsertIsvaConfigRequest req;
            try {
                req = objectMapper.convertValue(entry.path("config"), UpsertIsvaConfigRequest.class);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid 'config' for bootstrap isva entry [" + slug + "]: " + e.getMessage(), e);
            }
            Set<ConstraintViolation<UpsertIsvaConfigRequest>> violations = validator.validate(req);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted().collect(Collectors.joining("; "));
                throw new IllegalStateException(
                        "Validation failed for bootstrap isva entry [" + slug + "]: " + detail);
            }
            UUID directoryId = isvaConfigService.resolveDirectoryIdBySlug(slug);
            isvaConfigService.upsert(directoryId, req, null);
            applied++;
        }
        if (applied > 0) {
            log.info("Bootstrap config: applied {} ISVA config entr{}", applied, applied == 1 ? "y" : "ies");
        }
    }
}
