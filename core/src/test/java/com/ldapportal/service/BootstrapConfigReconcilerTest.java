// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.config.AppProperties;
import com.ldapportal.core.bootstrap.BootstrapConfigContributor;
import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BootstrapConfigReconcilerTest {

    private final DirectoryConnectionService directoryService = mock(DirectoryConnectionService.class);
    private final AdminManagementService adminService = mock(AdminManagementService.class);
    private final ApplicationSettingsService settingsService = mock(ApplicationSettingsService.class);
    private final AuditDataSourceService auditSourceService = mock(AuditDataSourceService.class);
    private final BootstrapConfigContributor contributor = mock(BootstrapConfigContributor.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private MockEnvironment environment;
    private AppProperties props;
    private BootstrapConfigReconciler reconciler;

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        props = new AppProperties();
        reconciler = new BootstrapConfigReconciler(
                props, environment, objectMapper, validator,
                directoryService, adminService, settingsService, auditSourceService,
                List.of(contributor));
    }

    private void run() {
        reconciler.run(new DefaultApplicationArguments());
    }

    private Path writeConfig(String yaml) throws Exception {
        Path f = tmp.resolve("bootstrap.yml");
        Files.writeString(f, yaml);
        props.getBootstrap().setConfigFile(f.toString());
        return f;
    }

    @Test
    void noConfigFile_isNoOp() {
        run();   // configFile left null
        verifyNoInteractions(directoryService, adminService, settingsService,
                auditSourceService, contributor);
    }

    @Test
    void unreadableFile_failsFast() {
        props.getBootstrap().setConfigFile(tmp.resolve("does-not-exist.yml").toString());
        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable");
    }

    @Test
    void validConfig_upsertsDirectoriesAdmins_andInvokesContributors() throws Exception {
        writeConfig("""
                directories:
                  - slug: corp-ldap
                    displayName: Corp LDAP
                    directoryType: GENERIC
                    host: ldap.corp.example.com
                    port: 636
                    sslMode: LDAPS
                    bindDn: "cn=svc,dc=corp,dc=example,dc=com"
                    bindPassword: "${CORP_BIND_PW}"
                    baseDn: "dc=corp,dc=example,dc=com"
                    pagingSize: 500
                    poolMinSize: 2
                    poolMaxSize: 20
                    poolConnectTimeoutSeconds: 10
                    poolResponseTimeoutSeconds: 30
                    enabled: true
                admins:
                  - account:
                      username: jdoe
                      role: ADMIN
                      authType: LOCAL
                      displayName: Jane Doe
                      active: true
                    profileRoles: []
                    featurePermissions: []
                """);
        environment.setProperty("CORP_BIND_PW", "s3cret");

        run();

        ArgumentCaptor<DirectoryConnectionRequest> dir =
                ArgumentCaptor.forClass(DirectoryConnectionRequest.class);
        verify(directoryService).upsertBySlug(eq("corp-ldap"), dir.capture());
        assertThat(dir.getValue().port()).isEqualTo(636);
        assertThat(dir.getValue().bindPassword()).isEqualTo("s3cret");   // ${ENV} interpolated

        verify(adminService).upsertByUsername(eq("jdoe"), any(), isNull());
        verify(contributor).contribute(any());
    }

    @Test
    void settingsSection_isUpsertedWithInterpolatedSecret() throws Exception {
        writeConfig("""
                settings:
                  appName: "Acme Portal"
                  sessionTimeoutMinutes: 30
                  smtpUseTls: true
                  s3PresignedUrlTtlHours: 24
                  smtpHost: smtp.acme.example.com
                  smtpPassword: "${SMTP_PW}"
                  enabledAuthTypes: [LOCAL, OIDC]
                """);
        environment.setProperty("SMTP_PW", "relaysecret");

        run();

        ArgumentCaptor<com.ldapportal.dto.settings.UpdateApplicationSettingsRequest> cap =
                ArgumentCaptor.forClass(com.ldapportal.dto.settings.UpdateApplicationSettingsRequest.class);
        verify(settingsService).upsert(cap.capture());
        assertThat(cap.getValue().appName()).isEqualTo("Acme Portal");
        assertThat(cap.getValue().sessionTimeoutMinutes()).isEqualTo(30);
        assertThat(cap.getValue().smtpPassword()).isEqualTo("relaysecret");   // ${ENV} interpolated
    }

    @Test
    void auditSourcesSection_isUpsertedBySlug_withInterpolatedSecret() throws Exception {
        writeConfig("""
                auditDataSources:
                  - slug: dsee-audit
                    displayName: DSEE Audit
                    host: audit.corp.example.com
                    port: 636
                    sslMode: LDAPS
                    bindDn: "cn=reader,dc=corp,dc=example,dc=com"
                    bindPassword: "${AUDIT_PW}"
                    changelogBaseDn: "cn=changelog"
                    changelogFormat: DSEE_CHANGELOG
                    enabled: true
                """);
        environment.setProperty("AUDIT_PW", "auditsecret");

        run();

        ArgumentCaptor<com.ldapportal.dto.audit.AuditSourceRequest> cap =
                ArgumentCaptor.forClass(com.ldapportal.dto.audit.AuditSourceRequest.class);
        verify(auditSourceService).upsertBySlug(eq("dsee-audit"), cap.capture());
        assertThat(cap.getValue().host()).isEqualTo("audit.corp.example.com");
        assertThat(cap.getValue().bindPassword()).isEqualTo("auditsecret");   // ${ENV} interpolated
    }

    @Test
    void auditSourceMissingSlug_failsFast() throws Exception {
        writeConfig("""
                auditDataSources:
                  - displayName: No Slug
                    host: x
                    port: 636
                    sslMode: LDAPS
                    bindDn: x
                    bindPassword: x
                    changelogBaseDn: "cn=changelog"
                    changelogFormat: DSEE_CHANGELOG
                    enabled: true
                """);

        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slug");
        verify(auditSourceService, never()).upsertBySlug(any(), any());
    }

    @Test
    void invalidSettings_failsFast_withoutWriting() throws Exception {
        // appName is @NotBlank and sessionTimeoutMinutes is @NotNull @Min(1);
        // omitting them must abort before any upsert.
        writeConfig("""
                settings:
                  smtpHost: smtp.acme.example.com
                  smtpUseTls: false
                  s3PresignedUrlTtlHours: 24
                """);

        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Validation failed");
        verify(settingsService, never()).upsert(any());
    }

    @Test
    void invalidDirectory_failsFast_withoutWriting() throws Exception {
        // port omitted -> primitive 0 -> fails @Min(1); must abort before any upsert.
        writeConfig("""
                directories:
                  - slug: bad
                    displayName: Bad
                    bindDn: x
                    baseDn: x
                    host: x
                """);

        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Validation failed");
        verify(directoryService, never()).upsertBySlug(any(), any());
    }

    @Test
    void directoryMissingSlug_failsFast() throws Exception {
        writeConfig("""
                directories:
                  - displayName: No Slug
                    host: x
                    port: 389
                    sslMode: NONE
                    bindDn: x
                    bindPassword: x
                    baseDn: x
                    pagingSize: 500
                    poolMinSize: 1
                    poolMaxSize: 5
                    poolConnectTimeoutSeconds: 10
                    poolResponseTimeoutSeconds: 30
                    enabled: true
                """);

        assertThatThrownBy(this::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slug");
        verify(directoryService, never()).upsertBySlug(any(), any());
    }

    @Test
    void unresolvedEnvPlaceholder_failsFast() throws Exception {
        writeConfig("""
                directories:
                  - slug: corp-ldap
                    displayName: "${MISSING_VAR}"
                    host: x
                    port: 389
                    sslMode: NONE
                    bindDn: x
                    bindPassword: x
                    baseDn: x
                    pagingSize: 500
                    poolMinSize: 1
                    poolMaxSize: 5
                    poolConnectTimeoutSeconds: 10
                    poolResponseTimeoutSeconds: 30
                    enabled: true
                """);

        assertThatThrownBy(this::run).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(directoryService);
    }
}
