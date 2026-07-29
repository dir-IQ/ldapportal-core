// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.core.bootstrap.ConfigExportContributor;
import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;
import com.ldapportal.dto.admin.FeaturePermissionRequest;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Serializes a live LDAPPortal install into the declarative YAML that the
 * {@link BootstrapConfigReconciler} consumes — the inverse of that reconciler
 * and the missing "export" half of the IaC surface described in
 * {@code docs/plans/2026-06-05-iac-automation-design.md}. The dump can be
 * committed to Git as infrastructure-as-code or stored for disaster recovery,
 * then fed back to a fresh install via {@code BOOTSTRAP_CONFIG_FILE} (or the
 * by-key REST upserts) to reconstruct the configuration.
 *
 * <p><b>Secrets are never exported.</b> Bind passwords, Entra client secrets
 * and admin passwords are write-only (they can't be read back), so each stored
 * credential is emitted as a {@code ${ENV_VAR}} placeholder that the reconciler
 * resolves from the environment at restore time. This keeps the dump safe to
 * commit; the operator supplies the actual secrets separately (secret manager /
 * encrypted vars file). The header lists every placeholder that must be set.</p>
 *
 * <p><b>Phase 1 scope.</b> This covers exactly the sections the reconciler can
 * already apply: {@code directories}, {@code admins} (account + admin-wide
 * feature permissions), and — via {@link ConfigExportContributor} — vendor
 * sections such as {@code isva}. Profile-scoped admin permissions, application
 * settings, audit sources, sync config and the rest are deliberately excluded
 * until the reconciler learns to apply them (see the design doc); exporting a
 * reference the reconciler can't yet resolve would produce a dump that fails to
 * restore. The golden invariant is that everything emitted here round-trips
 * cleanly back through the reconciler.</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigExportService {

    private final DirectoryConnectionService directoryService;
    private final AdminManagementService adminService;
    private final ObjectMapper objectMapper;
    private final List<ConfigExportContributor> contributors;

    /**
     * Build the export document and render it as a YAML string, prefixed with a
     * header that enumerates the {@code ${ENV_VAR}} placeholders an operator
     * must supply at restore time.
     */
    public String exportYaml() {
        // TreeSet so the header lists the required secret env vars sorted and
        // de-duplicated; collected as a side effect of building the sections.
        TreeSet<String> requiredEnv = new TreeSet<>();
        Map<String, Object> root = new LinkedHashMap<>();

        List<Map<String, Object>> directories = exportDirectories(requiredEnv);
        if (!directories.isEmpty()) {
            root.put("directories", directories);
        }
        List<Map<String, Object>> admins = exportAdmins(requiredEnv);
        if (!admins.isEmpty()) {
            root.put("admins", admins);
        }

        // Addon-owned sections (e.g. isva). Appended after the core sections so
        // a vendor section that references a directory sits below it — matching
        // the reconciler's apply order.
        contributors.forEach(c -> c.export(root));

        return header(requiredEnv) + dump(root);
    }

    // ── directories ─────────────────────────────────────────────────────────

    private List<Map<String, Object>> exportDirectories(TreeSet<String> requiredEnv) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DirectoryConnectionService.DirectoryExport export : directoryService.exportAll()) {
            Map<String, Object> entry = objectMapper.convertValue(
                    export.request(), new TypeReference<LinkedHashMap<String, Object>>() {});
            // Drop null-valued keys (unset optional fields, and the secrets /
            // auditDataSourceId we deliberately cleared) to keep the YAML clean.
            entry.values().removeIf(java.util.Objects::isNull);

            String slug = export.request().slug();
            if (export.bindPasswordSet()) {
                String var = envVar("DIR", slug, "BIND_PASSWORD");
                requiredEnv.add(var);
                entry.put("bindPassword", placeholder(var));
            }
            if (export.entraClientSecretSet()) {
                String var = envVar("DIR", slug, "ENTRA_CLIENT_SECRET");
                requiredEnv.add(var);
                entry.put("entraClientSecret", placeholder(var));
            }
            out.add(entry);
        }
        return out;
    }

    // ── admins ──────────────────────────────────────────────────────────────

    private List<Map<String, Object>> exportAdmins(TreeSet<String> requiredEnv) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<AdminAccountResponse> admins = adminService.listAdmins().stream()
                // The reconciler's by-username upsert manages ADMIN accounts
                // only; superadmins come from the bootstrap-superadmin env and
                // are out of scope.
                .filter(a -> a.role() == AccountRole.ADMIN)
                .sorted(java.util.Comparator.comparing(AdminAccountResponse::username))
                .toList();

        for (AdminAccountResponse admin : admins) {
            // Build the account through the same request record the reconciler
            // reads (with the same ObjectMapper), so enum fields (role,
            // authType) serialize exactly as they'll be parsed back — no
            // hand-rolled token that could drift from Jackson's form.
            AdminAccountRequest accountReq = new AdminAccountRequest(
                    admin.username(),
                    admin.displayName(),
                    admin.email(),
                    AccountRole.ADMIN,
                    admin.authType(),
                    null,                                             // password — placeholder added below
                    admin.authType() == AccountType.LDAP ? admin.ldapDn() : null,
                    admin.active());
            Map<String, Object> account = objectMapper.convertValue(
                    accountReq, new TypeReference<LinkedHashMap<String, Object>>() {});
            account.values().removeIf(java.util.Objects::isNull);
            if (admin.authType() == AccountType.LOCAL && admin.passwordSet()) {
                String var = envVar("ADMIN", admin.username(), "PASSWORD");
                requiredEnv.add(var);
                account.put("password", placeholder(var));
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("account", account);
            // Profile roles reference provisioning-profile UUIDs, which this
            // phase doesn't export; emit an empty list so the reconciler's
            // full-replace leaves none dangling. Only admin-wide feature
            // overrides (no profile scope) are portable today.
            entry.put("profileRoles", List.of());
            entry.put("featurePermissions", adminWideFeatures(admin));
            out.add(entry);
        }
        return out;
    }

    private List<Map<String, Object>> adminWideFeatures(AdminAccountResponse admin) {
        AdminPermissionsResponse perms = adminService.getPermissions(admin.id());
        List<Map<String, Object>> features = new ArrayList<>();
        perms.featurePermissions().stream()
                .filter(f -> f.profileId() == null)   // admin-wide only
                .sorted(java.util.Comparator.comparing(f -> f.featureKey().name()))
                .forEach(f -> {
                    // Round-trip via the request record so featureKey serializes
                    // exactly as the reconciler will deserialize it.
                    FeaturePermissionRequest req =
                            new FeaturePermissionRequest(f.featureKey(), f.enabled());
                    Map<String, Object> m = objectMapper.convertValue(
                            req, new TypeReference<LinkedHashMap<String, Object>>() {});
                    m.values().removeIf(java.util.Objects::isNull);   // drop null profileId
                    features.add(m);
                });
        return features;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a deterministic, upper-snake-case environment-variable name for a
     * secret placeholder, e.g. {@code LDAPPORTAL_DIR_CORP_LDAP_BIND_PASSWORD}.
     * Any character outside {@code [A-Z0-9]} in the key becomes an underscore.
     */
    private static String envVar(String kind, String key, String suffix) {
        String sanitized = key == null ? "" : key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        sanitized = sanitized.replaceAll("(^_+)|(_+$)", "");
        return "LDAPPORTAL_" + kind + "_" + sanitized + "_" + suffix;
    }

    private static String placeholder(String envVar) {
        return "${" + envVar + "}";
    }

    private static String header(TreeSet<String> requiredEnv) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SPDX-License-Identifier: Apache-2.0\n");
        sb.append("# LDAPPortal configuration export.\n");
        sb.append("# Generated by GET /api/v1/superadmin/config/export.\n");
        sb.append("#\n");
        sb.append("# Restore by pointing BOOTSTRAP_CONFIG_FILE at this file (reconciled at\n");
        sb.append("# startup) or by feeding the sections to the by-key REST upserts. See\n");
        sb.append("# docs/iac/README.md.\n");
        sb.append("#\n");
        sb.append("# Secrets are NOT included. Each ${VAR} below is resolved from the\n");
        sb.append("# environment at restore time; an unset placeholder aborts startup.\n");
        sb.append("# Required secret environment variables:\n");
        if (requiredEnv.isEmpty()) {
            sb.append("#   (none)\n");
        } else {
            requiredEnv.forEach(v -> sb.append("#   - ").append(v).append('\n'));
        }
        sb.append('\n');
        return sb.toString();
    }

    private String dump(Map<String, Object> root) {
        if (root.isEmpty()) {
            return "{}\n";
        }
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        opts.setIndicatorIndent(1);
        opts.setIndentWithIndicator(true);
        return new Yaml(opts).dump(root);
    }
}
