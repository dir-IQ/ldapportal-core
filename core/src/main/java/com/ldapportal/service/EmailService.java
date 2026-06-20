// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Sends plaintext and single-attachment emails over SMTP using the configured
 * {@link ApplicationSettings} (raw socket, no JavaMail dependency). When SMTP is
 * not configured the send is logged and skipped — never thrown — so callers can
 * fire-and-forget.
 *
 * <p>Extracted from {@code ApprovalNotificationService} so both the approval
 * notifier and the scheduled-report scheduler share one SMTP implementation
 * rather than duplicating the socket protocol.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_SMTP_PORT = 587;

    private final ApplicationSettingsService appSettingsService;
    private final EncryptionService encryptionService;

    /** Sends a plaintext email. Logged-and-skipped when SMTP is not configured. */
    public void send(String to, String subject, String body) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (!smtpConfigured(settings)) {
            log.info("SMTP not configured — notification logged: to={}, subject={}", to, subject);
            return;
        }
        try {
            sendSmtpEmail(settings, to, subject, body);
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", to, ex.getMessage());
        }
    }

    /** Sends an email with one MIME attachment. Logged-and-skipped when SMTP is not configured. */
    public void sendWithAttachment(String to, String subject, String body,
                                   String attachmentName, String attachmentContentType, byte[] attachmentData) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (!smtpConfigured(settings)) {
            log.info("SMTP not configured — attachment email logged: to={}, subject={}", to, subject);
            return;
        }
        try {
            sendSmtpEmailWithAttachment(settings, to, subject, body,
                    attachmentName, attachmentContentType, attachmentData);
        } catch (Exception ex) {
            log.error("Failed to send email with attachment to {}: {}", to, ex.getMessage());
        }
    }

    private static boolean smtpConfigured(ApplicationSettings settings) {
        return settings.getSmtpHost() != null && !settings.getSmtpHost().isBlank()
                && settings.getSmtpSenderAddress() != null && !settings.getSmtpSenderAddress().isBlank();
    }

    private void sendSmtpEmail(ApplicationSettings settings, String to, String subject, String body) throws Exception {
        String host = settings.getSmtpHost();
        int port = settings.getSmtpPort() != null ? settings.getSmtpPort() : DEFAULT_SMTP_PORT;
        String from = settings.getSmtpSenderAddress();

        Socket socket = new Socket(host, port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        try {
            readLine(in);
            out.println("EHLO ldapportal");
            readMultiLine(in);

            authenticate(settings, in, out);

            out.println("MAIL FROM:<" + from + ">");
            readLine(in);
            out.println("RCPT TO:<" + to + ">");
            readLine(in);
            out.println("DATA");
            readLine(in);
            out.println("From: " + from);
            out.println("To: " + to);
            out.println("Subject: " + subject);
            out.println("Content-Type: text/plain; charset=UTF-8");
            out.println();
            out.println(body);
            out.println(".");
            readLine(in);
            out.println("QUIT");
        } finally {
            socket.close();
        }
    }

    private void sendSmtpEmailWithAttachment(ApplicationSettings settings, String to, String subject,
                                             String body, String attachmentName, String attachmentContentType,
                                             byte[] attachmentData) throws Exception {
        String host = settings.getSmtpHost();
        int port = settings.getSmtpPort() != null ? settings.getSmtpPort() : DEFAULT_SMTP_PORT;
        String from = settings.getSmtpSenderAddress();
        String boundary = "----=_LDAPPortal_" + System.currentTimeMillis();

        Socket socket = new Socket(host, port);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        try {
            readLine(in);
            out.println("EHLO ldapportal");
            readMultiLine(in);

            authenticate(settings, in, out);

            out.println("MAIL FROM:<" + from + ">");
            readLine(in);
            out.println("RCPT TO:<" + to + ">");
            readLine(in);
            out.println("DATA");
            readLine(in);

            out.println("From: " + from);
            out.println("To: " + to);
            out.println("Subject: " + subject);
            out.println("MIME-Version: 1.0");
            out.println("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"");
            out.println();
            // Text body part
            out.println("--" + boundary);
            out.println("Content-Type: text/plain; charset=UTF-8");
            out.println();
            out.println(body);
            out.println();
            // Attachment part
            out.println("--" + boundary);
            out.println("Content-Type: " + attachmentContentType + "; name=\"" + attachmentName + "\"");
            out.println("Content-Disposition: attachment; filename=\"" + attachmentName + "\"");
            out.println("Content-Transfer-Encoding: base64");
            out.println();
            out.println(Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(attachmentData));
            out.println();
            out.println("--" + boundary + "--");
            out.println(".");
            readLine(in);
            out.println("QUIT");
        } finally {
            socket.close();
        }
    }

    private void authenticate(ApplicationSettings settings, BufferedReader in, PrintWriter out) throws Exception {
        if (settings.getSmtpUsername() != null && settings.getSmtpPasswordEncrypted() != null) {
            String password = encryptionService.decrypt(settings.getSmtpPasswordEncrypted());
            String auth = Base64.getEncoder().encodeToString(
                    ("\0" + settings.getSmtpUsername() + "\0" + password).getBytes(StandardCharsets.UTF_8));
            out.println("AUTH PLAIN " + auth);
            readLine(in);
        }
    }

    private String readLine(BufferedReader in) throws Exception {
        String line = in.readLine();
        if (line != null && line.length() >= 4 && line.charAt(3) == '-') {
            readMultiLine(in);
        }
        return line;
    }

    private void readMultiLine(BufferedReader in) throws Exception {
        String line;
        do {
            line = in.readLine();
        } while (line != null && line.length() >= 4 && line.charAt(3) == '-');
    }
}
