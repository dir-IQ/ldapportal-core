// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Sends plaintext and single-attachment emails over SMTP using the configured
 * {@link ApplicationSettings} (raw socket, no JavaMail dependency). When SMTP is
 * not configured the send is logged and skipped — never thrown — so callers can
 * fire-and-forget.
 *
 * <p>Extracted from {@code ApprovalNotificationService} so both the approval
 * notifier and the scheduled-report scheduler share one SMTP implementation
 * rather than duplicating the socket protocol.</p>
 *
 * <p>When {@code smtpUseTls} is set the connection is encrypted before any
 * credentials or message content are sent: implicit TLS on port 465, otherwise
 * {@code STARTTLS} after the first {@code EHLO}. A server that refuses
 * {@code STARTTLS} fails the send — it never falls back to cleartext. The
 * server certificate is validated against the JVM trust store and the
 * configured host name.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final int IMPLICIT_TLS_PORT = 465;
    private static final String CRLF = "\r\n";

    private final ApplicationSettingsService appSettingsService;
    private final EncryptionService encryptionService;

    // Not final, so not constructor-injected; package-private so tests can
    // point the client at a local server with a self-signed certificate.
    SocketFactory plainSocketFactory = SocketFactory.getDefault();
    SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

    /** Sends a plaintext email. Logged-and-skipped when SMTP is not configured. */
    public void send(String to, String subject, String body) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (!smtpConfigured(settings)) {
            log.info("SMTP not configured — notification logged: to={}, subject={}", to, subject);
            return;
        }
        try {
            deliver(settings, to, subject, out -> writePlainMessage(out, body));
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", to, ex.getMessage());
        }
    }

    /**
     * Outcome of an attempted attachment send, so callers that care about
     * delivery (the scheduled-report run log) can record the truth instead of
     * assuming success. Fire-and-forget callers can ignore the return.
     */
    public enum SendResult {
        /** Accepted by the SMTP server (a 2xx reply to RCPT TO and to the message). */
        SENT,
        /** The send was attempted but rejected or errored. */
        FAILED,
        /** Not attempted because SMTP is not configured. */
        SKIPPED
    }

    /** Outcome of {@link #sendTest}: whether the server accepted the message, and why not. */
    public record TestResult(boolean ok, String message) {
    }

    /**
     * Sends an email with one MIME attachment and reports the outcome. When SMTP
     * is not configured the send is skipped (and {@link SendResult#SKIPPED}
     * returned) rather than silently succeeding; a transport error or a non-2xx
     * SMTP reply yields {@link SendResult#FAILED}.
     */
    public SendResult sendWithAttachment(String to, String subject, String body,
                                         String attachmentName, String attachmentContentType, byte[] attachmentData) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (!smtpConfigured(settings)) {
            log.info("SMTP not configured — attachment email logged: to={}, subject={}", to, subject);
            return SendResult.SKIPPED;
        }
        try {
            deliver(settings, to, subject, out -> writeAttachmentMessage(out, body,
                    attachmentName, attachmentContentType, attachmentData));
            return SendResult.SENT;
        } catch (SmtpRejectedException ex) {
            log.error("SMTP rejected attachment email to {}: {}", to, ex.getMessage());
            return SendResult.FAILED;
        } catch (Exception ex) {
            log.error("Failed to send email with attachment to {}: {}", to, ex.getMessage());
            return SendResult.FAILED;
        }
    }

    /**
     * Sends a test message with the saved SMTP settings and reports the outcome
     * in operator-readable form (the failing SMTP reply or transport error), so
     * the settings page can verify a configuration before relying on it.
     *
     * @throws IllegalArgumentException when {@code to} is not a usable address
     */
    public TestResult sendTest(String to) {
        if (to == null || to.isBlank() || to.contains("\r") || to.contains("\n") || !to.contains("@")) {
            throw new IllegalArgumentException("A valid recipient email address is required");
        }
        String recipient = to.trim();
        ApplicationSettings settings = appSettingsService.getEntity();
        List<String> missing = new ArrayList<>();
        if (isBlank(settings.getSmtpHost())) missing.add("host");
        if (isBlank(settings.getSmtpSenderAddress())) missing.add("sender address");
        if (!missing.isEmpty()) {
            return new TestResult(false, "SMTP is not configured — missing " + String.join(" and ", missing)
                    + ". Save the settings before testing.");
        }
        String body = "This is a test message from LDAPPortal confirming that the SMTP settings work."
                + CRLF + CRLF + "Server: " + settings.getSmtpHost() + ":" + port(settings)
                + CRLF + "Encryption: " + (settings.isSmtpUseTls() ? tlsMode(settings) : "none")
                + CRLF + "Authentication: " + (hasCredentials(settings) ? "AUTH PLAIN as " + settings.getSmtpUsername() : "none");
        try {
            deliver(settings, recipient, "LDAPPortal SMTP test", out -> writePlainMessage(out, body));
            return new TestResult(true, "Test email accepted by " + settings.getSmtpHost() + " for " + recipient);
        } catch (SmtpRejectedException ex) {
            return new TestResult(false, ex.getMessage());
        } catch (SSLException ex) {
            return new TestResult(false, "TLS handshake failed: " + ex.getMessage());
        } catch (Exception ex) {
            return new TestResult(false, "Could not connect to " + settings.getSmtpHost() + ":" + port(settings)
                    + " — " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private static boolean smtpConfigured(ApplicationSettings settings) {
        return !isBlank(settings.getSmtpHost()) && !isBlank(settings.getSmtpSenderAddress());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean hasCredentials(ApplicationSettings settings) {
        return settings.getSmtpUsername() != null && settings.getSmtpPasswordEncrypted() != null;
    }

    private static int port(ApplicationSettings settings) {
        return settings.getSmtpPort() != null ? settings.getSmtpPort() : DEFAULT_SMTP_PORT;
    }

    private static boolean implicitTls(ApplicationSettings settings) {
        return settings.isSmtpUseTls() && port(settings) == IMPLICIT_TLS_PORT;
    }

    private static String tlsMode(ApplicationSettings settings) {
        return implicitTls(settings) ? "implicit TLS" : "STARTTLS";
    }

    /** Writes the message headers and body (everything between DATA and the terminating dot). */
    @FunctionalInterface
    private interface MessageWriter {
        void write(Writer out) throws IOException;
    }

    /**
     * Runs one SMTP conversation and returns normally only when the server
     * accepted the message. Any unexpected reply throws
     * {@link SmtpRejectedException}; transport and TLS failures throw their
     * {@link IOException}.
     */
    private void deliver(ApplicationSettings settings, String to, String subject, MessageWriter content)
            throws IOException {
        String host = settings.getSmtpHost();
        int port = port(settings);
        String from = settings.getSmtpSenderAddress();

        Socket socket = implicitTls(settings) ? connectTls(host, port) : connectPlain(host, port);
        try {
            SmtpConnection smtp = new SmtpConnection(socket);
            smtp.expect("server greeting", smtp.readReply(), 220);
            smtp.command("EHLO ldapportal", 250);

            if (settings.isSmtpUseTls() && !implicitTls(settings)) {
                smtp.command("STARTTLS", 220);
                socket = upgradeToTls(socket, host, port);
                smtp = new SmtpConnection(socket);
                // RFC 3207: the client must discard prior knowledge and re-issue EHLO.
                smtp.command("EHLO ldapportal", 250);
            }

            if (hasCredentials(settings)) {
                String password = encryptionService.decrypt(settings.getSmtpPasswordEncrypted());
                String auth = Base64.getEncoder().encodeToString(
                        ("\0" + settings.getSmtpUsername() + "\0" + password).getBytes(StandardCharsets.UTF_8));
                smtp.command("AUTH PLAIN " + auth, "AUTH", 235);
            }

            smtp.command("MAIL FROM:<" + from + ">", 250);
            smtp.command("RCPT TO:<" + to + ">", 250, 251);
            smtp.command("DATA", 354);

            Writer data = new DotStuffingWriter(smtp.out);
            data.write("From: " + from + CRLF);
            data.write("To: " + to + CRLF);
            data.write("Subject: " + headerSafe(subject) + CRLF);
            content.write(data);
            data.flush();
            smtp.command(CRLF + ".", "end of message", 250);

            smtp.sendQuietly("QUIT");
        } finally {
            socket.close();
        }
    }

    private Socket connectPlain(String host, int port) throws IOException {
        Socket socket = plainSocketFactory.createSocket();
        socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
    }

    private Socket connectTls(String host, int port) throws IOException {
        return upgradeToTls(connectPlain(host, port), host, port);
    }

    /** Layers TLS over an open socket, verifying the certificate against {@code host}. */
    private Socket upgradeToTls(Socket plain, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(plain, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.setSoTimeout(SOCKET_TIMEOUT_MS);
        ssl.startHandshake();
        return ssl;
    }

    private static String headerSafe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }

    private static void writePlainMessage(Writer out, String body) throws IOException {
        out.write("Content-Type: text/plain; charset=UTF-8" + CRLF);
        out.write(CRLF);
        out.write(body);
    }

    private static void writeAttachmentMessage(Writer out, String body, String attachmentName,
                                               String attachmentContentType, byte[] attachmentData)
            throws IOException {
        String boundary = "----=_LDAPPortal_" + System.currentTimeMillis();
        out.write("MIME-Version: 1.0" + CRLF);
        out.write("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"" + CRLF);
        out.write(CRLF);
        // Text body part
        out.write("--" + boundary + CRLF);
        out.write("Content-Type: text/plain; charset=UTF-8" + CRLF);
        out.write(CRLF);
        out.write(body + CRLF);
        out.write(CRLF);
        // Attachment part
        out.write("--" + boundary + CRLF);
        out.write("Content-Type: " + attachmentContentType + "; name=\"" + attachmentName + "\"" + CRLF);
        out.write("Content-Disposition: attachment; filename=\"" + attachmentName + "\"" + CRLF);
        out.write("Content-Transfer-Encoding: base64" + CRLF);
        out.write(CRLF);
        out.write(Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII)).encodeToString(attachmentData) + CRLF);
        out.write(CRLF);
        out.write("--" + boundary + "--");
    }

    /** An SMTP reply that was not one of the codes the conversation expected. */
    static final class SmtpRejectedException extends IOException {
        SmtpRejectedException(String message) {
            super(message);
        }
    }

    /** Line-level SMTP I/O over one socket (recreated after a STARTTLS upgrade). */
    private static final class SmtpConnection {
        private final BufferedReader in;
        private final Writer out;

        SmtpConnection(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        void command(String line, int... accepted) throws IOException {
            command(line, line.split(" ", 2)[0], accepted);
        }

        /** Sends {@code line}, naming it {@code label} in errors so secrets (AUTH) never leak into messages. */
        void command(String line, String label, int... accepted) throws IOException {
            out.write(line + CRLF);
            out.flush();
            expect(label, readReply(), accepted);
        }

        void sendQuietly(String line) {
            try {
                out.write(line + CRLF);
                out.flush();
            } catch (IOException ignored) {
                // The message is already accepted; a failed QUIT changes nothing.
            }
        }

        /** Reads a (possibly multi-line) reply and returns its final line. */
        String readReply() throws IOException {
            String line;
            do {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("Connection closed by SMTP server");
                }
            } while (line.length() >= 4 && line.charAt(3) == '-');
            return line;
        }

        void expect(String label, String reply, int... accepted) throws SmtpRejectedException {
            int code = -1;
            if (reply.length() >= 3) {
                try {
                    code = Integer.parseInt(reply.substring(0, 3));
                } catch (NumberFormatException ignored) {
                    // falls through as unexpected
                }
            }
            for (int ok : accepted) {
                if (code == ok) return;
            }
            throw new SmtpRejectedException("SMTP server rejected " + label + ": " + reply);
        }
    }

    /**
     * Applies SMTP transparency (RFC 5321 §4.5.2) to message content: a line
     * starting with "." gets an extra "." so it cannot end the DATA section
     * early, and bare LFs are normalised to CRLF.
     */
    private static final class DotStuffingWriter extends Writer {
        private final Writer target;
        private boolean atLineStart = true;
        private char prev;

        DotStuffingWriter(Writer target) {
            this.target = target;
        }

        @Override
        public void write(char[] buf, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                char c = buf[i];
                if (c == '\n' && prev != '\r') {
                    target.write('\r');
                }
                if (atLineStart && c == '.') {
                    target.write('.');
                }
                target.write(c);
                atLineStart = c == '\n';
                prev = c;
            }
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
