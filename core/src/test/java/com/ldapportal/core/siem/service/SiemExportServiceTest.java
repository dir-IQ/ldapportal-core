// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.entity.enums.SiemFormat;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.service.ApplicationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiemExportServiceTest {

    @Mock private ApplicationSettingsService settingsService;
    @Mock private SiemFormatter              formatter;
    @Mock private SiemClient                 client;
    @Mock private AuditEventRepository       auditEventRepo;

    private SiemExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new SiemExportService(settingsService, formatter, client, auditEventRepo);
    }

    // ── export ──────────────────────────────────────────────────────────────

    @Test
    void export_siemDisabled_doesNotSend() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemEnabled(false);
        when(settingsService.getEntity()).thenReturn(settings);

        exportService.export(testEvent());

        verify(formatter, never()).format(any(), any());
        verify(client, never()).send(any(), any());
    }

    @Test
    void export_noProtocol_doesNotSend() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemEnabled(true);
        settings.setSiemProtocol(null);
        settings.setSiemFormat(SiemFormat.JSON);
        when(settingsService.getEntity()).thenReturn(settings);

        exportService.export(testEvent());

        verify(client, never()).send(any(), any());
    }

    @Test
    void export_noFormat_doesNotSend() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemEnabled(true);
        settings.setSiemProtocol(SiemProtocol.SYSLOG_UDP);
        settings.setSiemFormat(null);
        when(settingsService.getEntity()).thenReturn(settings);

        exportService.export(testEvent());

        verify(client, never()).send(any(), any());
    }

    @Test
    void export_fullyConfigured_formatsAndSends() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), eq(SiemFormat.CEF))).thenReturn("CEF:formatted");

        AuditEvent event = testEvent();
        exportService.export(event);

        verify(formatter).format(event, SiemFormat.CEF);
        verify(client).send(settings, "CEF:formatted");
    }

    @Test
    void export_formatterThrows_doesNotPropagate() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), any())).thenThrow(new RuntimeException("format error"));

        // Must not throw
        exportService.export(testEvent());

        verify(client, never()).send(any(), any());
    }

    @Test
    void export_usesCache_doesNotQuerySettingsEveryCall() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), any())).thenReturn("formatted");

        // Call export twice rapidly
        exportService.export(testEvent());
        exportService.export(testEvent());

        // Settings should only be fetched once (cached)
        verify(settingsService, times(1)).getEntity();
    }

    @Test
    void export_afterCacheInvalidation_refetchesSettings() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), any())).thenReturn("formatted");

        exportService.export(testEvent());
        exportService.invalidateCache();
        exportService.export(testEvent());

        // Settings fetched twice: before invalidation and after
        verify(settingsService, times(2)).getEntity();
    }

    // ── sendTestEvent ───────────────────────────────────────────────────────

    @Test
    void sendTestEvent_siemDisabled_returnsMessage() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemEnabled(false);
        when(settingsService.getEntity()).thenReturn(settings);

        String result = exportService.sendTestEvent();

        assertThat(result).contains("not enabled");
        verify(client, never()).send(any(), any());
    }

    @Test
    void sendTestEvent_configured_sendsAndReturnsSuccess() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), eq(SiemFormat.CEF))).thenReturn("CEF:test");

        String result = exportService.sendTestEvent();

        assertThat(result).contains("successfully");
        verify(client).send(eq(settings), eq("CEF:test"));
    }

    @Test
    void sendTestEvent_sendThrows_returnsError() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), any())).thenReturn("formatted");
        doThrow(new RuntimeException("network down")).when(client).send(any(), any());

        String result = exportService.sendTestEvent();

        assertThat(result).contains("failed").contains("network down");
    }

    @Test
    void sendTestEvent_usesIntegrityCheckAction() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(formatter.format(any(), any())).thenReturn("formatted");

        exportService.sendTestEvent();

        // Verify the test event uses INTEGRITY_CHECK (not LDAP_CHANGE)
        verify(formatter).format(argThat(event ->
                event.getAction() == AuditAction.INTEGRITY_CHECK
                && event.getDetail() != null
                && event.getDetail().containsKey("type")), any());
    }

    // ── testConnectivity ────────────────────────────────────────────────────

    @Test
    void testConnectivity_delegatesToClient() {
        ApplicationSettings settings = enabledSettings();
        when(settingsService.getEntity()).thenReturn(settings);
        when(client.testConnectivity(settings)).thenReturn("TCP: Connected");

        String result = exportService.testConnectivity();

        assertThat(result).isEqualTo("TCP: Connected");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AuditEvent testEvent() {
        return AuditEvent.builder()
                .id(UUID.randomUUID())
                .source(AuditSource.INTERNAL)
                .action(AuditAction.USER_CREATE)
                .actorUsername("alice")
                .targetDn("uid=alice,dc=corp")
                .occurredAt(OffsetDateTime.now())
                .build();
    }

    private ApplicationSettings enabledSettings() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemEnabled(true);
        settings.setSiemProtocol(SiemProtocol.SYSLOG_TCP);
        settings.setSiemHost("siem.corp.com");
        settings.setSiemPort(6514);
        settings.setSiemFormat(SiemFormat.CEF);
        return settings;
    }
}
