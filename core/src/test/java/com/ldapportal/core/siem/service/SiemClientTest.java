// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class SiemClientTest {

    @Mock private EncryptionService encryptionService;

    private SiemClient client;

    @BeforeEach
    void setUp() {
        client = new SiemClient(encryptionService);
    }

    // ── testConnectivity ────────────────────────────────────────────────────

    @Test
    void testConnectivity_noProtocol_returnsNotConfigured() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(null);

        String result = client.testConnectivity(settings);

        assertThat(result).contains("No SIEM protocol configured");
    }

    @Test
    void testConnectivity_udp_resolvesDns() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.SYSLOG_UDP);
        settings.setSiemHost("localhost");
        settings.setSiemPort(514);

        String result = client.testConnectivity(settings);

        assertThat(result).contains("UDP").contains("DNS resolved");
    }

    @Test
    void testConnectivity_webhook_noUrl_returnsNotConfigured() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.WEBHOOK);
        settings.setWebhookUrl(null);

        String result = client.testConnectivity(settings);

        assertThat(result).contains("Webhook URL not configured");
    }

    @Test
    void testConnectivity_webhook_withUrl_returnsConfigured() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.WEBHOOK);
        settings.setWebhookUrl("https://hooks.example.com/audit");

        String result = client.testConnectivity(settings);

        assertThat(result).contains("Webhook").contains("https://hooks.example.com/audit");
    }

    @Test
    void testConnectivity_tcp_unreachableHost_returnsError() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.SYSLOG_TCP);
        // Use a non-routable address to trigger timeout/error
        settings.setSiemHost("192.0.2.1");
        settings.setSiemPort(514);

        String result = client.testConnectivity(settings);

        assertThat(result).contains("Connection test failed");
    }

    @Test
    void testConnectivity_tls_unreachableHost_returnsError() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.SYSLOG_TLS);
        settings.setSiemHost("192.0.2.1");
        settings.setSiemPort(6514);

        String result = client.testConnectivity(settings);

        assertThat(result).contains("Connection test failed");
    }

    // ── send ────────────────────────────────────────────────────────────────

    @Test
    void send_nullProtocol_doesNotThrow() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(null);

        assertThatCode(() -> client.send(settings, "test message"))
                .doesNotThrowAnyException();
    }

    @Test
    void send_udpToLocalhost_doesNotThrow() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.SYSLOG_UDP);
        settings.setSiemHost("localhost");
        settings.setSiemPort(19999); // unlikely to have a listener — UDP doesn't care

        assertThatCode(() -> client.send(settings, "test syslog message"))
                .doesNotThrowAnyException();
    }

    @Test
    void send_webhookNoUrl_doesNotThrow() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSiemProtocol(SiemProtocol.WEBHOOK);
        settings.setWebhookUrl(null);

        assertThatCode(() -> client.send(settings, "test webhook"))
                .doesNotThrowAnyException();
    }
}
