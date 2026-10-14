// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-tests EmailService against an in-process scripted SMTP server: the
 * not-configured guard, the plain and STARTTLS conversations (including the
 * no-cleartext-fallback rule), reply checking, and the settings-page test send.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    @TempDir static Path tempDir;
    private static SSLContext serverTls;
    private static SSLContext clientTls;

    @Mock private ApplicationSettingsService appSettingsService;
    @Mock private EncryptionService encryptionService;
    @InjectMocks private EmailService emailService;

    private FakeSmtpServer server;

    /** Generates a throwaway self-signed "localhost" certificate with the JDK keytool. */
    @BeforeAll
    static void createCertificate() throws Exception {
        Path keystore = tempDir.resolve("smtp.p12");
        Process p = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", new String(STORE_PASSWORD))
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as("keytool: %s", output).isZero();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keystore.toFile())) {
            ks.load(in, STORE_PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASSWORD);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("smtp", ks.getCertificate("smtp"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) server.close();
    }

    private ApplicationSettings settings(boolean useTls) {
        ApplicationSettings s = new ApplicationSettings();
        s.setSmtpHost("localhost");
        s.setSmtpPort(server.port());
        s.setSmtpSenderAddress("portal@example.com");
        s.setSmtpUseTls(useTls);
        return s;
    }

    // ── not configured ──────────────────────────────────────────────────────

    @Test
    void send_whenSmtpNotConfigured_logsAndSkips() {
        when(appSettingsService.getEntity()).thenReturn(new ApplicationSettings());

        assertThatCode(() -> emailService.send("ops@example.com", "subject", "body"))
                .doesNotThrowAnyException();
        verifyNoInteractions(encryptionService);
    }

    @Test
    void sendWithAttachment_whenSmtpNotConfigured_skipsWithoutThrowing() {
        when(appSettingsService.getEntity()).thenReturn(new ApplicationSettings());

        // Reports SKIPPED (not SENT) so a missing mailer never masquerades as a
        // delivered report in the scheduled-report run log.
        EmailService.SendResult result = emailService.sendWithAttachment(
                "ops@example.com", "subject", "body", "report.csv", "text/csv", new byte[]{1, 2, 3});

        assertThat(result).isEqualTo(EmailService.SendResult.SKIPPED);
        verifyNoInteractions(encryptionService);
    }

    // ── plain SMTP ──────────────────────────────────────────────────────────

    @Test
    void sendWithAttachment_hostAndSenderOnly_sendsWithoutAuthOrTls() throws Exception {
        server = FakeSmtpServer.start();
        when(appSettingsService.getEntity()).thenReturn(settings(false));

        EmailService.SendResult result = emailService.sendWithAttachment(
                "ops@example.com", "Weekly report", "see attached", "report.csv", "text/csv", new byte[]{1, 2, 3});

        server.awaitDone();
        assertThat(result).isEqualTo(EmailService.SendResult.SENT);
        assertThat(server.commands).containsExactly(
                "EHLO ldapportal", "MAIL FROM:<portal@example.com>", "RCPT TO:<ops@example.com>", "DATA", "QUIT");
        assertThat(server.data).contains("Subject: Weekly report", "filename=\"report.csv\"", "AQID");
        verifyNoInteractions(encryptionService);
    }

    @Test
    void send_dotStuffsLinesStartingWithDot() throws Exception {
        server = FakeSmtpServer.start();
        when(appSettingsService.getEntity()).thenReturn(settings(false));

        emailService.send("ops@example.com", "subject", "first\n.hidden\nlast");

        server.awaitDone();
        assertThat(server.dataLines).containsSubsequence("first", "..hidden", "last");
    }

    @Test
    void sendWithAttachment_rejectedRecipient_reportsFailed() throws Exception {
        server = FakeSmtpServer.start();
        server.rcptReply = "550 5.1.1 No such user";
        when(appSettingsService.getEntity()).thenReturn(settings(false));

        EmailService.SendResult result = emailService.sendWithAttachment(
                "nobody@example.com", "s", "b", "r.csv", "text/csv", new byte[]{1});

        server.awaitDone();
        assertThat(result).isEqualTo(EmailService.SendResult.FAILED);
        assertThat(server.commands).doesNotContain("DATA");
    }

    // ── STARTTLS ────────────────────────────────────────────────────────────

    @Test
    void sendWithAttachment_useTls_upgradesWithStartTlsBeforeSendingMail() throws Exception {
        server = FakeSmtpServer.start();
        emailService.sslSocketFactory = clientTls.getSocketFactory();
        when(appSettingsService.getEntity()).thenReturn(settings(true));

        EmailService.SendResult result = emailService.sendWithAttachment(
                "ops@example.com", "s", "b", "r.csv", "text/csv", new byte[]{1});

        server.awaitDone();
        assertThat(result).isEqualTo(EmailService.SendResult.SENT);
        assertThat(server.commands).containsExactly(
                "EHLO ldapportal", "STARTTLS", "EHLO ldapportal",
                "MAIL FROM:<portal@example.com>", "RCPT TO:<ops@example.com>", "DATA", "QUIT");
        // Everything from the second EHLO on travelled over TLS.
        assertThat(server.firstTlsCommandIndex).isEqualTo(2);
    }

    @Test
    void sendWithAttachment_useTls_serverRefusesStartTls_failsWithoutCleartextFallback() throws Exception {
        server = FakeSmtpServer.start();
        server.starttlsReply = "454 TLS not available";
        when(appSettingsService.getEntity()).thenReturn(settings(true));

        EmailService.SendResult result = emailService.sendWithAttachment(
                "ops@example.com", "s", "b", "r.csv", "text/csv", new byte[]{1});

        server.awaitDone();
        assertThat(result).isEqualTo(EmailService.SendResult.FAILED);
        assertThat(server.commands).containsExactly("EHLO ldapportal", "STARTTLS");
    }

    @Test
    void sendWithAttachment_useTlsWithCredentials_authenticatesOnlyAfterTls() throws Exception {
        server = FakeSmtpServer.start();
        emailService.sslSocketFactory = clientTls.getSocketFactory();
        ApplicationSettings s = settings(true);
        s.setSmtpUsername("relay");
        s.setSmtpPasswordEncrypted("enc");
        when(appSettingsService.getEntity()).thenReturn(s);
        when(encryptionService.decrypt("enc")).thenReturn("secret");

        EmailService.SendResult result = emailService.sendWithAttachment(
                "ops@example.com", "s", "b", "r.csv", "text/csv", new byte[]{1});

        server.awaitDone();
        assertThat(result).isEqualTo(EmailService.SendResult.SENT);
        int auth = server.indexOfPrefix("AUTH PLAIN ");
        assertThat(auth).isGreaterThan(server.commands.indexOf("STARTTLS"));
        assertThat(auth).isGreaterThanOrEqualTo(server.firstTlsCommandIndex);
    }

    // ── sendTest ────────────────────────────────────────────────────────────

    @Test
    void sendTest_success_reportsAccepted() throws Exception {
        server = FakeSmtpServer.start();
        emailService.sslSocketFactory = clientTls.getSocketFactory();
        when(appSettingsService.getEntity()).thenReturn(settings(true));

        EmailService.TestResult result = emailService.sendTest("ops@example.com");

        server.awaitDone();
        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("accepted", "ops@example.com");
        assertThat(server.data).contains("Subject: LDAPPortal SMTP test", "Encryption: STARTTLS");
    }

    @Test
    void sendTest_untrustedCertificate_reportsHandshakeFailure() throws Exception {
        server = FakeSmtpServer.start();
        // Default JVM trust store: the self-signed test certificate is not trusted.
        when(appSettingsService.getEntity()).thenReturn(settings(true));

        EmailService.TestResult result = emailService.sendTest("ops@example.com");

        server.awaitDone();
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).startsWith("TLS handshake failed");
        assertThat(server.commands).doesNotContain("MAIL FROM:<portal@example.com>");
    }

    @Test
    void sendTest_serverRefusesStartTls_reportsReply() throws Exception {
        server = FakeSmtpServer.start();
        server.starttlsReply = "454 TLS not available";
        when(appSettingsService.getEntity()).thenReturn(settings(true));

        EmailService.TestResult result = emailService.sendTest("ops@example.com");

        server.awaitDone();
        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo("SMTP server rejected STARTTLS: 454 TLS not available");
    }

    @Test
    void sendTest_missingSender_reportsWhatIsMissing() {
        ApplicationSettings s = new ApplicationSettings();
        s.setSmtpHost("smtp.example.com");
        when(appSettingsService.getEntity()).thenReturn(s);

        EmailService.TestResult result = emailService.sendTest("ops@example.com");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("missing sender address");
    }

    @Test
    void sendTest_recipientWithLineBreak_isRejected() {
        assertThatThrownBy(() -> emailService.sendTest("ops@example.com\r\nRCPT TO:<x@evil.com>"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(appSettingsService);
    }

    /**
     * A single-connection scripted SMTP server. Records each command, the DATA
     * section, and the index of the first command received over TLS.
     */
    private static final class FakeSmtpServer implements AutoCloseable {
        final ServerSocket serverSocket;
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final List<String> dataLines = Collections.synchronizedList(new ArrayList<>());
        volatile String data = "";
        volatile int firstTlsCommandIndex = -1;
        volatile String starttlsReply = "220 Ready to start TLS";
        volatile String rcptReply = "250 OK";
        private Thread thread;

        private FakeSmtpServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        }

        static FakeSmtpServer start() throws IOException {
            FakeSmtpServer s = new FakeSmtpServer();
            s.thread = new Thread(s::serve, "fake-smtp");
            s.thread.setDaemon(true);
            s.thread.start();
            return s;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int indexOfPrefix(String prefix) {
            synchronized (commands) {
                for (int i = 0; i < commands.size(); i++) {
                    if (commands.get(i).startsWith(prefix)) return i;
                }
            }
            return -1;
        }

        void awaitDone() throws InterruptedException {
            thread.join(10_000);
            assertThat(thread.isAlive()).as("fake SMTP server finished").isFalse();
        }

        private void serve() {
            try (Socket accepted = serverSocket.accept()) {
                accepted.setSoTimeout(5_000);
                Socket socket = accepted;
                BufferedReader in = reader(socket);
                Writer out = writer(socket);
                reply(out, "220 fake ESMTP");
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line);
                    if (line.startsWith("EHLO")) {
                        reply(out, "250-fake\r\n250-STARTTLS\r\n250 AUTH PLAIN");
                    } else if (line.equals("STARTTLS")) {
                        reply(out, starttlsReply);
                        if (!starttlsReply.startsWith("220")) continue;
                        SSLSocket ssl = (SSLSocket) serverTls.getSocketFactory()
                                .createSocket(socket, null, socket.getPort(), true);
                        ssl.setUseClientMode(false);
                        ssl.startHandshake();
                        socket = ssl;
                        in = reader(socket);
                        out = writer(socket);
                        firstTlsCommandIndex = commands.size();
                    } else if (line.startsWith("AUTH")) {
                        reply(out, "235 Authenticated");
                    } else if (line.startsWith("MAIL FROM")) {
                        reply(out, "250 OK");
                    } else if (line.startsWith("RCPT TO")) {
                        reply(out, rcptReply);
                    } else if (line.equals("DATA")) {
                        reply(out, "354 Go ahead");
                        StringBuilder sb = new StringBuilder();
                        String d;
                        while ((d = in.readLine()) != null && !d.equals(".")) {
                            dataLines.add(d);
                            sb.append(d).append('\n');
                        }
                        data = sb.toString();
                        reply(out, "250 Queued");
                    } else if (line.equals("QUIT")) {
                        reply(out, "221 Bye");
                        return;
                    } else {
                        reply(out, "500 Unknown command");
                    }
                }
            } catch (IOException ignored) {
                // Client hung up (e.g. after a refused STARTTLS or failed handshake).
            }
        }

        private static BufferedReader reader(Socket s) throws IOException {
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        }

        private static Writer writer(Socket s) throws IOException {
            return new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        }

        private static void reply(Writer out, String text) throws IOException {
            out.write(text + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
