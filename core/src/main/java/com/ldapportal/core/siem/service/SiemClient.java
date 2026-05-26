// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.SiemFormat;
import com.ldapportal.entity.enums.SiemProtocol;
import com.ldapportal.service.EncryptionService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Transport layer for delivering formatted audit events to SIEM targets.
 * Supports UDP syslog, TCP syslog, TLS syslog (RFC 5425), and HTTPS webhook.
 *
 * <p>TCP/TLS connections are maintained persistently and reused across calls
 * to avoid per-event connection overhead. The shared HttpClient is similarly
 * reused for webhook delivery.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SiemClient {

    private final EncryptionService encryptionService;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RETRIES = 3;
    private static final int MAX_UDP_SAFE_SIZE = 1024; // RFC 5426 recommendation

    // Persistent TCP/TLS socket (guarded by synchronized methods)
    private volatile Socket persistentSocket;
    private volatile OutputStream persistentOut;
    private volatile String persistentHost;
    private volatile int persistentPort;
    private volatile boolean persistentTls;

    // Shared HTTP client for webhook
    private volatile HttpClient sharedHttpClient;

    @PreDestroy
    void shutdown() {
        closePersistentSocket();
        if (sharedHttpClient != null) {
            sharedHttpClient.close();
        }
    }

    /**
     * Sends a formatted message to the configured SIEM target.
     */
    public void send(ApplicationSettings settings, String message) {
        SiemProtocol protocol = settings.getSiemProtocol();
        if (protocol == null) {
            log.warn("SIEM protocol not configured, skipping export");
            return;
        }

        switch (protocol) {
            case SYSLOG_UDP -> sendUdp(settings.getSiemHost(), settings.getSiemPort(), message);
            case SYSLOG_TCP -> sendTcpWithRetry(settings.getSiemHost(), settings.getSiemPort(), message, false);
            case SYSLOG_TLS -> sendTcpWithRetry(settings.getSiemHost(), settings.getSiemPort(), message, true);
            case WEBHOOK    -> sendWebhookWithRetry(settings, message);
        }
    }

    // ── UDP ──────────────────────────────────────────────────────────────────

    private void sendUdp(String host, Integer port, String message) {
        int targetPort = port != null ? port : 514;
        byte[] data = message.getBytes(StandardCharsets.UTF_8);

        if (data.length > MAX_UDP_SAFE_SIZE) {
            log.warn("SIEM UDP message size ({} bytes) exceeds recommended maximum ({} bytes) — "
                    + "message may be truncated by network equipment. Consider TCP/TLS for large events.",
                    data.length, MAX_UDP_SAFE_SIZE);
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket packet = new DatagramPacket(data, data.length, addr, targetPort);
            socket.send(packet);
        } catch (Exception e) {
            log.error("Failed to send syslog UDP to {}:{}: {}", host, targetPort, e.getMessage());
        }
    }

    // ── TCP / TLS with persistent connection and retry ───────────────────────

    private synchronized void sendTcpWithRetry(String host, Integer port, String message, boolean tls) {
        int targetPort = port != null ? port : (tls ? 6514 : 514);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ensureConnected(host, targetPort, tls);

                // RFC 6587 octet-counting framing
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                String frame = data.length + " ";
                persistentOut.write(frame.getBytes(StandardCharsets.UTF_8));
                persistentOut.write(data);
                persistentOut.flush();
                return; // success
            } catch (Exception e) {
                closePersistentSocket();
                if (attempt < MAX_RETRIES) {
                    log.warn("SIEM TCP/TLS send attempt {}/{} failed ({}:{}) — retrying: {}",
                            attempt, MAX_RETRIES, host, targetPort, e.getMessage());
                    sleepQuietly(attempt * 1000L);
                } else {
                    log.error("SIEM TCP/TLS send failed after {} attempts to {}:{}: {}",
                            MAX_RETRIES, host, targetPort, e.getMessage());
                }
            }
        }
    }

    private void ensureConnected(String host, int port, boolean tls) throws Exception {
        if (persistentSocket != null && !persistentSocket.isClosed()
                && persistentSocket.isConnected()
                && host.equals(persistentHost) && port == persistentPort
                && tls == persistentTls) {
            return; // reuse existing connection
        }

        closePersistentSocket();

        Socket socket;
        if (tls) {
            var sslFactory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) sslFactory.createSocket();
            // Enable hostname verification ("HTTPS" algorithm). Without this,
            // SSLSocketFactory.getDefault() validates the cert is signed by a
            // trusted CA but DOESN'T check the cert's CN/SAN matches `host`,
            // letting a TCP-path MITM present a valid-but-wrong cert. (CodeQL
            // java/unsafe-cert-trust). Forcing HTTPS endpoint-id makes the JDK
            // perform RFC 2818 hostname verification on the handshake.
            javax.net.ssl.SSLParameters params = sslSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(params);
            socket = sslSocket;
        } else {
            socket = new Socket();
        }

        socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
        socket.setSoTimeout((int) REQUEST_TIMEOUT.toMillis());

        persistentSocket = socket;
        persistentOut = socket.getOutputStream();
        persistentHost = host;
        persistentPort = port;
        persistentTls = tls;

        log.info("SIEM {} connection established to {}:{}", tls ? "TLS" : "TCP", host, port);
    }

    private void closePersistentSocket() {
        if (persistentSocket != null) {
            try {
                persistentSocket.close();
            } catch (Exception ignored) {}
            persistentSocket = null;
            persistentOut = null;
        }
    }

    // ── Webhook with retry ──────────────────────────────────────────────────

    private void sendWebhookWithRetry(ApplicationSettings settings, String message) {
        String url = settings.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.warn("Webhook URL not configured, skipping");
            return;
        }
        com.ldapportal.util.UrlValidator.requireSafeUrl(url);

        // Determine Content-Type based on format
        String contentType = switch (settings.getSiemFormat()) {
            case JSON -> "application/json";
            case CEF, LEEF -> "text/plain";
            case RFC5424 -> "application/syslog";
            case null -> "text/plain";
        };

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));

                if (settings.getWebhookAuthHeaderEnc() != null) {
                    String authHeader = encryptionService.decrypt(settings.getWebhookAuthHeaderEnc());
                    reqBuilder.header("Authorization", authHeader);
                }

                HttpResponse<String> resp = getHttpClient().send(reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() < 400) {
                    return; // success
                }

                // 4xx errors are not retryable
                if (resp.statusCode() < 500) {
                    log.error("Webhook returned HTTP {} (non-retryable): {}", resp.statusCode(), resp.body());
                    return;
                }

                // 5xx — retryable
                log.warn("Webhook returned HTTP {} on attempt {}/{}: {}",
                        resp.statusCode(), attempt, MAX_RETRIES, resp.body());
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("Webhook send attempt {}/{} failed — retrying: {}",
                            attempt, MAX_RETRIES, e.getMessage());
                } else {
                    log.error("Webhook send failed after {} attempts to {}: {}",
                            MAX_RETRIES, url, e.getMessage());
                    return;
                }
            }

            sleepQuietly(attempt * 1000L);
        }
    }

    private HttpClient getHttpClient() {
        if (sharedHttpClient == null) {
            synchronized (this) {
                if (sharedHttpClient == null) {
                    sharedHttpClient = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .build();
                }
            }
        }
        return sharedHttpClient;
    }

    // ── Connectivity test ───────────────────────────────────────────────────

    /**
     * Tests connectivity to the configured SIEM target.
     * Returns a human-readable result message.
     */
    public String testConnectivity(ApplicationSettings settings) {
        SiemProtocol protocol = settings.getSiemProtocol();
        if (protocol == null) return "No SIEM protocol configured.";

        try {
            switch (protocol) {
                case SYSLOG_UDP -> {
                    InetAddress.getByName(settings.getSiemHost());
                    return "UDP: DNS resolved " + settings.getSiemHost() + " successfully. "
                            + "Note: UDP delivery cannot be confirmed.";
                }
                case SYSLOG_TCP, SYSLOG_TLS -> {
                    boolean tls = protocol == SiemProtocol.SYSLOG_TLS;
                    int port = settings.getSiemPort() != null ? settings.getSiemPort() : (tls ? 6514 : 514);
                    Socket socket;
                    if (tls) {
                        var sslFactory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                        socket = sslFactory.createSocket();
                    } else {
                        socket = new Socket();
                    }
                    try {
                        socket.connect(new InetSocketAddress(settings.getSiemHost(), port),
                                (int) CONNECT_TIMEOUT.toMillis());
                    } finally {
                        socket.close();
                    }
                    String label = tls ? "TLS" : "TCP";
                    return label + ": Connected to " + settings.getSiemHost() + ":" + port + " successfully.";
                }
                case WEBHOOK -> {
                    String url = settings.getWebhookUrl();
                    if (url == null || url.isBlank()) return "Webhook URL not configured.";
                    return "Webhook: URL " + url + " configured. Send a test event to verify delivery.";
                }
            }
        } catch (Exception e) {
            return "Connection test failed: " + e.getMessage();
        }
        return "Unknown protocol.";
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
