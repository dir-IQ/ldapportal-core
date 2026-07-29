// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;
import com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest;
import com.ldapportal.dto.directory.BaseDnRequest;
import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.entity.enums.SslMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConfigExportService}. The load-bearing assertion is the
 * <em>round-trip</em>: every section the exporter emits must parse straight back
 * into the request records the {@link BootstrapConfigReconciler} validates and
 * applies, with no constraint violations. If that holds, an exported dump
 * restores cleanly.
 */
class ConfigExportServiceTest {

    private final DirectoryConnectionService directoryService = mock(DirectoryConnectionService.class);
    private final AdminManagementService adminService = mock(AdminManagementService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final ConfigExportService service = new ConfigExportService(
            directoryService, adminService, objectMapper, List.of());

    private DirectoryConnectionRequest ldapRequest() {
        return new DirectoryConnectionRequest(
                DirectoryType.OPENLDAP, "Corp LDAP", "ldap.corp.example.com", 636,
                SslMode.LDAPS, false, "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----",
                "cn=svc,dc=corp,dc=example,dc=com", null, "dc=corp,dc=example,dc=com",
                500, 2, 20, 10, 30,
                null, null, null, null,
                null,                    // auditDataSourceId — exporter always clears this
                true, false, "uid",
                null, null, null,
                List.of(new BaseDnRequest("ou=People,dc=corp,dc=example,dc=com", 0)),
                List.of(new BaseDnRequest("ou=Groups,dc=corp,dc=example,dc=com", 0)),
                List.of("inetOrgPerson"), List.of("groupOfNames"),
                null, null, null, null,
                "corp-ldap");
    }

    private AdminAccountResponse adminResponse(String username, AccountRole role,
                                               AccountType authType, boolean passwordSet) {
        Instant now = Instant.now();
        return new AdminAccountResponse(UUID.randomUUID(), 1L, username, "Display " + username,
                username + "@example.com", role, authType, null, true, now, now, now, passwordSet);
    }

    @Test
    void exportsDirectory_asReconcilerConsumableYaml_withSecretPlaceholder() {
        when(directoryService.exportAll()).thenReturn(List.of(
                new DirectoryConnectionService.DirectoryExport(ldapRequest(), true, false)));
        when(adminService.listAdmins()).thenReturn(List.of());

        String yaml = service.exportYaml();
        Map<String, Object> root = new Yaml().load(yaml);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dirs = (List<Map<String, Object>>) root.get("directories");
        assertThat(dirs).hasSize(1);
        Map<String, Object> dir = dirs.get(0);

        // Secret is a placeholder, never a real value; auditDataSourceId dropped.
        assertThat(dir.get("bindPassword")).isEqualTo("${LDAPPORTAL_DIR_CORP_LDAP_BIND_PASSWORD}");
        assertThat(dir).doesNotContainKey("auditDataSourceId");
        assertThat(dir.get("slug")).isEqualTo("corp-ldap");
        // The trusted cert PEM survives the round-trip (needed to reconnect).
        assertThat((String) dir.get("trustedCertificatePem")).contains("BEGIN CERTIFICATE");

        // Header enumerates the required secret env var.
        assertThat(yaml).contains("LDAPPORTAL_DIR_CORP_LDAP_BIND_PASSWORD");

        // Round-trip: the emitted map deserializes into a valid request.
        DirectoryConnectionRequest back = objectMapper.convertValue(dir, DirectoryConnectionRequest.class);
        Set<ConstraintViolation<DirectoryConnectionRequest>> violations = validator.validate(back);
        assertThat(violations).isEmpty();
        assertThat(back.slug()).isEqualTo("corp-ldap");
        assertThat(back.port()).isEqualTo(636);
        assertThat(back.userBaseDns()).hasSize(1);
    }

    @Test
    void exportsAdmins_filtersToAdminRole_placeholdersPassword_dropsProfileScopedFeatures() {
        AdminAccountResponse admin = adminResponse("jdoe", AccountRole.ADMIN, AccountType.LOCAL, true);
        AdminAccountResponse superadmin = adminResponse("root", AccountRole.SUPERADMIN, AccountType.LOCAL, true);
        when(directoryService.exportAll()).thenReturn(List.of());
        when(adminService.listAdmins()).thenReturn(List.of(admin, superadmin));
        when(adminService.getPermissions(admin.id())).thenReturn(new AdminPermissionsResponse(
                List.of(),
                List.of(
                        new AdminPermissionsResponse.FeatureOverride(FeatureKey.USER_CREATE, true, null),
                        // profile-scoped — must be dropped (profile not exported this phase)
                        new AdminPermissionsResponse.FeatureOverride(FeatureKey.USER_DELETE, true, UUID.randomUUID()))));

        String yaml = service.exportYaml();
        Map<String, Object> root = new Yaml().load(yaml);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> admins = (List<Map<String, Object>>) root.get("admins");
        assertThat(admins).hasSize(1);   // superadmin filtered out

        Map<String, Object> entry = admins.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) entry.get("account");
        assertThat(account.get("username")).isEqualTo("jdoe");
        assertThat(account.get("role")).isEqualTo("ADMIN");
        assertThat(account.get("password")).isEqualTo("${LDAPPORTAL_ADMIN_JDOE_PASSWORD}");
        assertThat(entry.get("profileRoles")).isEqualTo(List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> features = (List<Map<String, Object>>) entry.get("featurePermissions");
        assertThat(features).hasSize(1);   // admin-wide only
        assertThat(features.get(0)).doesNotContainKey("profileId");

        // Round-trip: the admin entry deserializes into a valid create request.
        CreateAdminWithPermissionsRequest back =
                objectMapper.convertValue(entry, CreateAdminWithPermissionsRequest.class);
        assertThat(validator.validate(back)).isEmpty();
        assertThat(back.account().username()).isEqualTo("jdoe");
        assertThat(back.account().role()).isEqualTo(AccountRole.ADMIN);
        assertThat(back.featurePermissionsOrEmpty()).hasSize(1);
        assertThat(back.featurePermissionsOrEmpty().get(0).featureKey()).isEqualTo(FeatureKey.USER_CREATE);
    }

    @Test
    void emptyInstall_producesValidEmptyDocument() {
        when(directoryService.exportAll()).thenReturn(List.of());
        when(adminService.listAdmins()).thenReturn(List.of());

        String yaml = service.exportYaml();
        assertThat(yaml).contains("(none)");            // no required secrets
        assertThat((Object) new Yaml().load(yaml)).isEqualTo(Map.of());   // parses to empty map
    }
}
