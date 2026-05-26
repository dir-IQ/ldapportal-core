// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.settings.ApplicationSettingsDto;
import com.ldapportal.dto.settings.UpdateApplicationSettingsRequest;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.SiemFormat;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.repository.ApplicationSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationSettingsServiceTest {

    @Mock private ApplicationSettingsRepository settingsRepo;
    @Mock private EncryptionService             encryptionService;

    private ApplicationSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationSettingsService(settingsRepo, encryptionService,
                new com.ldapportal.config.AppProperties());
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void get_existingSettings_returnsDto() {
        ApplicationSettings settings = existingSettings();
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings));

        ApplicationSettingsDto dto = service.get();

        assertThat(dto.appName()).isEqualTo("My App");
        assertThat(dto.sessionTimeoutMinutes()).isEqualTo(30);
        assertThat(dto.smtpPasswordConfigured()).isTrue();
        assertThat(dto.s3SecretKeyConfigured()).isFalse();
    }

    @Test
    void get_noSettingsExist_returnsDefaults() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());

        ApplicationSettingsDto dto = service.get();

        assertThat(dto.id()).isNull();
        assertThat(dto.appName()).isEqualTo("LDAP Portal");
        assertThat(dto.sessionTimeoutMinutes()).isEqualTo(60);
        assertThat(dto.smtpPasswordConfigured()).isFalse();
        // SIEM defaults
        assertThat(dto.siemEnabled()).isFalse();
        assertThat(dto.siemProtocol()).isNull();
        assertThat(dto.siemAuthTokenConfigured()).isFalse();
        assertThat(dto.webhookAuthHeaderConfigured()).isFalse();
    }

    @Test
    void get_existingSettings_returnsSiemFields() {
        ApplicationSettings settings = existingSettings();
        settings.setSiemEnabled(true);
        settings.setSiemProtocol(SiemProtocol.SYSLOG_UDP);
        settings.setSiemHost("siem.corp.com");
        settings.setSiemPort(514);
        settings.setSiemFormat(SiemFormat.CEF);
        settings.setSiemAuthTokenEnc("enc-token");
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings));

        ApplicationSettingsDto dto = service.get();

        assertThat(dto.siemEnabled()).isTrue();
        assertThat(dto.siemProtocol()).isEqualTo(SiemProtocol.SYSLOG_UDP);
        assertThat(dto.siemHost()).isEqualTo("siem.corp.com");
        assertThat(dto.siemPort()).isEqualTo(514);
        assertThat(dto.siemFormat()).isEqualTo(SiemFormat.CEF);
        assertThat(dto.siemAuthTokenConfigured()).isTrue();
        assertThat(dto.webhookAuthHeaderConfigured()).isFalse();
    }

    // ── upsert (create) ───────────────────────────────────────────────────────

    @Test
    void upsert_noExistingSettings_createsNew() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        when(encryptionService.encrypt("secret123")).thenReturn("enc-secret");
        when(settingsRepo.save(any(ApplicationSettings.class)))
                .thenAnswer(inv -> {
                    ApplicationSettings s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    return s;
                });

        UpdateApplicationSettingsRequest req = requestBuilder()
                .appName("New App").sessionTimeoutMinutes(45)
                .smtpHost("smtp.test.com").smtpPassword("secret123")
                .build();

        ApplicationSettingsDto dto = service.upsert(req);

        assertThat(dto.appName()).isEqualTo("New App");
        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().getSmtpPasswordEncrypted()).isEqualTo("enc-secret");
        assertThat(captor.getValue().getSessionTimeoutMinutes()).isEqualTo(45);
    }

    @Test
    void upsert_existingSettings_updatesRow() {
        ApplicationSettings existing = existingSettings();
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationSettingsDto dto = service.upsert(basicRequest());

        assertThat(dto.appName()).isEqualTo("My App");
        // password was null in request → preserved
        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().getSmtpPasswordEncrypted()).isEqualTo("encrypted-pw");
        verify(encryptionService, never()).encrypt(any());
    }

    // ── Password handling ─────────────────────────────────────────────────────

    @Test
    void upsert_emptyPassword_clearsEncryptedValue() {
        ApplicationSettings existing = existingSettings();
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateApplicationSettingsRequest req = requestBuilder()
                .smtpPassword("") // empty string → clear
                .build();

        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().getSmtpPasswordEncrypted()).isNull();
    }

    @Test
    void upsert_nonEmptyPassword_encryptsAndStores() {
        ApplicationSettings existing = existingSettings();
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("newpass")).thenReturn("enc-newpass");
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateApplicationSettingsRequest req = requestBuilder()
                .smtpPassword("newpass")
                .build();

        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().getSmtpPasswordEncrypted()).isEqualTo("enc-newpass");
    }

    // ── SIEM settings ─────────────────────────────────────────────────────────

    @Test
    void upsert_siemEnabled_storesConfiguration() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        when(encryptionService.encrypt("my-token")).thenReturn("enc-token");
        when(settingsRepo.save(any(ApplicationSettings.class)))
                .thenAnswer(inv -> {
                    ApplicationSettings s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    return s;
                });

        UpdateApplicationSettingsRequest req = requestBuilder()
                .siemEnabled(true)
                .siemProtocol(SiemProtocol.SYSLOG_TCP)
                .siemHost("siem.corp.com")
                .siemPort(6514)
                .siemFormat(SiemFormat.RFC5424)
                .siemAuthToken("my-token")
                .build();

        ApplicationSettingsDto dto = service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        ApplicationSettings saved = captor.getValue();
        assertThat(saved.isSiemEnabled()).isTrue();
        assertThat(saved.getSiemProtocol()).isEqualTo(SiemProtocol.SYSLOG_TCP);
        assertThat(saved.getSiemHost()).isEqualTo("siem.corp.com");
        assertThat(saved.getSiemPort()).isEqualTo(6514);
        assertThat(saved.getSiemFormat()).isEqualTo(SiemFormat.RFC5424);
        assertThat(saved.getSiemAuthTokenEnc()).isEqualTo("enc-token");
    }

    @Test
    void upsert_siemWebhook_storesWebhookConfig() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        when(encryptionService.encrypt("Bearer abc123")).thenReturn("enc-bearer");
        when(settingsRepo.save(any(ApplicationSettings.class)))
                .thenAnswer(inv -> {
                    ApplicationSettings s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    return s;
                });

        UpdateApplicationSettingsRequest req = requestBuilder()
                .siemEnabled(true)
                .siemProtocol(SiemProtocol.WEBHOOK)
                .siemFormat(SiemFormat.JSON)
                .webhookUrl("https://hooks.example.com/audit")
                .webhookAuthHeader("Bearer abc123")
                .build();

        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        ApplicationSettings saved = captor.getValue();
        assertThat(saved.getSiemProtocol()).isEqualTo(SiemProtocol.WEBHOOK);
        assertThat(saved.getWebhookUrl()).isEqualTo("https://hooks.example.com/audit");
        assertThat(saved.getWebhookAuthHeaderEnc()).isEqualTo("enc-bearer");
    }

    @Test
    void upsert_nullSiemAuthToken_preservesExisting() {
        ApplicationSettings existing = existingSettings();
        existing.setSiemAuthTokenEnc("old-enc-token");
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // null siemAuthToken → preserve
        UpdateApplicationSettingsRequest req = requestBuilder().build();

        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().getSiemAuthTokenEnc()).isEqualTo("old-enc-token");
        verify(encryptionService, never()).encrypt(any());
    }

    // ── Setup wizard flag ──────────────────────────────────────────────────────

    @Test
    void get_defaultSettings_setupCompletedIsFalse() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        ApplicationSettingsDto dto = service.get();
        assertThat(dto.setupCompleted()).isFalse();
    }

    @Test
    void get_existingSettings_returnsSetupCompleted() {
        ApplicationSettings settings = existingSettings();
        settings.setSetupCompleted(true);
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings));

        ApplicationSettingsDto dto = service.get();
        assertThat(dto.setupCompleted()).isTrue();
    }

    @Test
    void upsert_setupCompletedTrue_setsFlag() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        when(settingsRepo.save(any(ApplicationSettings.class)))
                .thenAnswer(inv -> {
                    ApplicationSettings s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    return s;
                });

        UpdateApplicationSettingsRequest req = requestBuilder()
                .setupCompleted(true)
                .build();

        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().isSetupCompleted()).isTrue();
    }

    @Test
    void upsert_nullSetupCompleted_preservesExisting() {
        ApplicationSettings existing = existingSettings();
        existing.setSetupCompleted(false);
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // null setupCompleted → preserve
        UpdateApplicationSettingsRequest req = requestBuilder().build();
        service.upsert(req);

        ArgumentCaptor<ApplicationSettings> captor =
                ArgumentCaptor.forClass(ApplicationSettings.class);
        verify(settingsRepo).save(captor.capture());
        assertThat(captor.getValue().isSetupCompleted()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ApplicationSettings existingSettings() {
        ApplicationSettings s = new ApplicationSettings();
        s.setId(UUID.randomUUID());
        s.setAppName("My App");
        s.setSessionTimeoutMinutes(30);
        s.setSmtpHost("smtp.example.com");
        s.setSmtpPasswordEncrypted("encrypted-pw");
        s.setS3SecretKeyEncrypted(null);
        return s;
    }

    private UpdateApplicationSettingsRequest basicRequest() {
        return requestBuilder().build();
    }

    /**
     * Builder helper to avoid huge constructor calls in every test.
     */
    private static RequestBuilder requestBuilder() {
        return new RequestBuilder();
    }

    private static class RequestBuilder {
        String appName = "My App";
        int sessionTimeoutMinutes = 30;
        String smtpHost = "smtp.example.com";
        Integer smtpPort = 587;
        String smtpSenderAddress = "noreply@example.com";
        String smtpUsername = "user";
        String smtpPassword = null;
        Boolean siemEnabled = null;
        SiemProtocol siemProtocol = null;
        String siemHost = null;
        Integer siemPort = null;
        SiemFormat siemFormat = null;
        String siemAuthToken = null;
        String webhookUrl = null;
        String webhookAuthHeader = null;
        Boolean setupCompleted = null;

        RequestBuilder appName(String v) { this.appName = v; return this; }
        RequestBuilder sessionTimeoutMinutes(int v) { this.sessionTimeoutMinutes = v; return this; }
        RequestBuilder smtpHost(String v) { this.smtpHost = v; return this; }
        RequestBuilder smtpPassword(String v) { this.smtpPassword = v; return this; }
        RequestBuilder siemEnabled(boolean v) { this.siemEnabled = v; return this; }
        RequestBuilder siemProtocol(SiemProtocol v) { this.siemProtocol = v; return this; }
        RequestBuilder siemHost(String v) { this.siemHost = v; return this; }
        RequestBuilder siemPort(int v) { this.siemPort = v; return this; }
        RequestBuilder siemFormat(SiemFormat v) { this.siemFormat = v; return this; }
        RequestBuilder siemAuthToken(String v) { this.siemAuthToken = v; return this; }
        RequestBuilder webhookUrl(String v) { this.webhookUrl = v; return this; }
        RequestBuilder webhookAuthHeader(String v) { this.webhookAuthHeader = v; return this; }
        RequestBuilder setupCompleted(Boolean v) { this.setupCompleted = v; return this; }

        UpdateApplicationSettingsRequest build() {
            return new UpdateApplicationSettingsRequest(
                    appName, null, "#fff", null,
                    null,    // directorySearchInlineEditEnabled (preserve)
                    sessionTimeoutMinutes,
                    smtpHost, smtpPort, smtpSenderAddress, smtpUsername, smtpPassword, true,
                    null, null, null, null, null, 24,
                    null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    siemEnabled, siemProtocol, siemHost, siemPort, siemFormat,
                    siemAuthToken, webhookUrl, webhookAuthHeader,
                    null, null, null, null, // webseal: trustedProxies, userHdr, groupsHdr, logoutUrl
                    setupCompleted);
        }
    }
}
