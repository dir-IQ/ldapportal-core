// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.config.AppProperties;
import com.ldapportal.core.bootstrap.BootstrapConfigContributor;
import com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest;
import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles LDAPPortal configuration from an optional declarative YAML file at
 * startup — the GitOps / air-gapped / config-baked-into-the-image half of the
 * IaC plan (Phase 2 of {@code docs/plans/2026-06-05-iac-automation-design.md}).
 *
 * <p>Opt-in: does nothing unless {@code app.bootstrap.config-file}
 * ({@code BOOTSTRAP_CONFIG_FILE}) points at a readable file, so existing
 * deployments are unaffected. When set, the file is read, {@code ${ENV_VAR}}
 * placeholders are resolved against the Spring environment (so secrets stay out
 * of the Git-tracked file), and the {@code directories} and {@code admins}
 * sections are applied through the <em>same</em> idempotent service upserts the
 * REST API uses — re-running converges, writing nothing when nothing changed.
 * Vendor sections (e.g. {@code isva}) are delegated to
 * {@link BootstrapConfigContributor} beans the addons register; with no addon
 * present they're ignored.</p>
 *
 * <p>Fail-fast: the whole file is parsed and bean-validated up front, so a
 * malformed config aborts startup before anything is written (mirroring how a
 * missing {@code ENCRYPTION_KEY}/{@code JWT_SECRET} aborts startup). This is
 * create/update only — it never deletes resources absent from the file; manage
 * deletions through the API / external tooling.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Order(100)   // after BootstrapService (the superadmin is created first)
public class BootstrapConfigReconciler implements ApplicationRunner {

    private final AppProperties appProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final DirectoryConnectionService directoryService;
    private final AdminManagementService adminService;
    private final List<BootstrapConfigContributor> contributors;

    @Override
    public void run(ApplicationArguments args) {
        String path = appProperties.getBootstrap().getConfigFile();
        if (path == null || path.isBlank()) {
            return;   // opt-in — unset is a no-op
        }
        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException(
                    "BOOTSTRAP_CONFIG_FILE is set but the file is not readable: " + path);
        }

        JsonNode root = parse(file, path);
        if (root == null || root.isNull() || root.isEmpty()) {
            log.info("Bootstrap config {} is empty — nothing to reconcile", path);
            return;
        }

        // Parse + validate everything before applying anything, so a malformed
        // file fails before the first write.
        List<DirectoryConnectionRequest> directories =
                readAndValidate(root.path("directories"), DirectoryConnectionRequest.class, "directories");
        directories.forEach(d -> {
            if (d.slug() == null || d.slug().isBlank()) {
                throw new IllegalStateException(
                        "Each bootstrap 'directories' entry must declare a non-blank 'slug'");
            }
        });
        List<CreateAdminWithPermissionsRequest> admins =
                readAndValidate(root.path("admins"), CreateAdminWithPermissionsRequest.class, "admins");

        // Apply core sections through the idempotent service upserts, then hand
        // the parsed tree to addon contributors (which may reference a directory
        // the core just created — e.g. ISVA config keyed by directory slug).
        directories.forEach(d -> directoryService.upsertBySlug(d.slug(), d));
        admins.forEach(a -> adminService.upsertByUsername(a.account().username(), a, null));
        contributors.forEach(c -> c.contribute(root));

        log.info("Bootstrap config reconciled from {}: {} director{}, {} admin{}, {} addon contributor(s)",
                path, directories.size(), directories.size() == 1 ? "y" : "ies",
                admins.size(), admins.size() == 1 ? "" : "s", contributors.size());
    }

    private JsonNode parse(Path file, String path) {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bootstrap config: " + path, e);
        }
        // ${ENV_VAR} / ${VAR:default} resolved against the Spring environment
        // (env vars, system properties, app config). An unresolved placeholder
        // throws — secrets must be supplied, not silently blank.
        String interpolated = environment.resolveRequiredPlaceholders(raw);
        Object loaded = new Yaml().load(interpolated);
        return loaded == null ? null : objectMapper.valueToTree(loaded);
    }

    private <T> List<T> readAndValidate(JsonNode node, Class<T> type, String section) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("Bootstrap config '" + section + "' must be a list");
        }
        List<T> out = new ArrayList<>();
        for (JsonNode entry : node) {
            T req;
            try {
                req = objectMapper.convertValue(entry, type);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Invalid entry in bootstrap '" + section + "': " + e.getMessage(), e);
            }
            Set<ConstraintViolation<T>> violations = validator.validate(req);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new IllegalStateException(
                        "Validation failed for a bootstrap '" + section + "' entry: " + detail);
            }
            out.add(req);
        }
        return out;
    }
}
