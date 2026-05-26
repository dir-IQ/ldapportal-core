// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.PendingApprovalRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.repository.ProfileApproverRepository;
import com.ldapportal.entity.ProfileApprover;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Sends email notifications for approval workflow events via SMTP.
 * Uses the SMTP config from {@link ApplicationSettings} with raw socket/HTTP.
 * If SMTP is not configured, notifications are logged instead.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalNotificationService {

    private final ApplicationSettingsService appSettingsService;
    private final EncryptionService encryptionService;
    private final ProvisioningProfileRepository profileRepo;
    private final ProfileApproverRepository approverRepo;
    private final AccountRepository accountRepo;
    private final PendingApprovalRepository approvalRepo;

    @Async
    public void notifyApproversOfNewRequest(PendingApproval approval) {
        String profileName = approval.getProfileId() != null
                ? profileRepo.findById(approval.getProfileId())
                        .map(ProvisioningProfile::getName).orElse("Unknown")
                : "Unknown";
        String requesterName = accountRepo.findById(approval.getRequestedBy())
                .map(Account::getUsername).orElse("Unknown");

        List<Account> approvers = approval.getProfileId() != null
                ? approverRepo.findAllByProfileIdWithAccount(approval.getProfileId()).stream()
                        .map(ProfileApprover::getAdminAccount).toList()
                : List.of();

        String subject = "[LDAPPortal] New approval request pending — " + profileName;
        String body = String.format(
                "A new %s request has been submitted by %s for profile '%s' and is awaiting your approval.\n\n"
                + "Request type: %s\nSubmitted: %s\n\n"
                + "Please log in to LDAPPortal to review and approve or reject this request.",
                approval.getRequestType().name(), requesterName, profileName,
                approval.getRequestType().name(), approval.getCreatedAt());

        for (Account approver : approvers) {
            if (approver.getEmail() != null && !approver.getEmail().isBlank()) {
                sendEmail(approver.getEmail(), subject, body);
            }
        }
    }

    @Async
    public void notifyRequesterApproved(PendingApproval approval) {
        String profileName = approval.getProfileId() != null
                ? profileRepo.findById(approval.getProfileId())
                        .map(ProvisioningProfile::getName).orElse("Unknown")
                : "Unknown";
        String reviewerName = approval.getReviewedBy() != null
                ? accountRepo.findById(approval.getReviewedBy())
                        .map(Account::getUsername).orElse("Unknown")
                : "Unknown";

        Account requester = accountRepo.findById(approval.getRequestedBy()).orElse(null);
        if (requester == null || requester.getEmail() == null) return;

        sendEmail(requester.getEmail(),
                "[LDAPPortal] Your request was approved — " + profileName,
                String.format("Your %s request for profile '%s' has been approved.\n\nReviewed by: %s",
                        approval.getRequestType().name(), profileName, reviewerName));
    }

    @Async
    public void notifyRequesterRejected(PendingApproval approval) {
        String profileName = approval.getProfileId() != null
                ? profileRepo.findById(approval.getProfileId())
                        .map(ProvisioningProfile::getName).orElse("Unknown")
                : "Unknown";
        String reviewerName = approval.getReviewedBy() != null
                ? accountRepo.findById(approval.getReviewedBy())
                        .map(Account::getUsername).orElse("Unknown")
                : "Unknown";
        String reason = approval.getRejectReason() != null ? approval.getRejectReason() : "No reason provided";

        Account requester = accountRepo.findById(approval.getRequestedBy()).orElse(null);
        if (requester == null || requester.getEmail() == null) return;

        sendEmail(requester.getEmail(),
                "[LDAPPortal] Your request was rejected — " + profileName,
                String.format("Your %s request for profile '%s' has been rejected.\n\nReason: %s\nReviewed by: %s",
                        approval.getRequestType().name(), profileName, reason, reviewerName));
    }

    @Scheduled(cron = "${ldapportal.approval.reminder-cron:0 0 9 * * *}")
    public void sendPendingReminders() {
        List<ProvisioningProfile> allProfiles = profileRepo.findAll();
        for (ProvisioningProfile profile : allProfiles) {
            long pendingCount = approvalRepo.countByProfileIdAndStatus(
                    profile.getId(), ApprovalStatus.PENDING);
            if (pendingCount == 0) continue;

            List<Account> approvers = approverRepo.findAllByProfileIdWithAccount(profile.getId()).stream()
                    .map(ProfileApprover::getAdminAccount).toList();
            for (Account approver : approvers) {
                if (approver.getEmail() != null && !approver.getEmail().isBlank()) {
                    sendEmail(approver.getEmail(),
                            String.format("[LDAPPortal] Reminder: %d pending approval(s) — %s",
                                    pendingCount, profile.getName()),
                            String.format("There are %d pending approval request(s) for profile '%s' awaiting your review.\n\n"
                                    + "Please log in to LDAPPortal to review them.",
                                    pendingCount, profile.getName()));
                }
            }
        }
    }

    @Async
    public void sendPasswordEmail(String recipientEmail, String userName, String password) {
        String subject = "[LDAPPortal] Your account has been created";
        String body = String.format(
                "Hello %s,\n\n"
                + "Your account has been created. Your temporary password is:\n\n"
                + "    %s\n\n"
                + "Please log in and change your password at your earliest convenience.\n\n"
                + "— LDAPPortal",
                userName, password);
        sendEmail(recipientEmail, subject, body);
    }

    @Async
    public void sendGenericEmail(String recipientEmail, String subject, String body) {
        sendEmail(recipientEmail, subject, body);
    }

    /**
     * Sends an email with a file attachment using MIME multipart encoding.
     */
    public void sendEmailWithAttachment(String recipientEmail, String subject, String body,
                                         String attachmentName, String attachmentContentType, byte[] attachmentData) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (settings.getSmtpHost() == null || settings.getSmtpHost().isBlank()
                || settings.getSmtpSenderAddress() == null || settings.getSmtpSenderAddress().isBlank()) {
            log.info("SMTP not configured — attachment email logged: to={}, subject={}", recipientEmail, subject);
            return;
        }

        try {
            sendSmtpEmailWithAttachment(settings, recipientEmail, subject, body,
                    attachmentName, attachmentContentType, attachmentData);
        } catch (Exception ex) {
            log.error("Failed to send email with attachment to {}: {}", recipientEmail, ex.getMessage());
        }
    }

    private void sendSmtpEmailWithAttachment(ApplicationSettings settings, String to, String subject,
                                              String body, String attachmentName, String attachmentContentType,
                                              byte[] attachmentData) throws Exception {
        String host = settings.getSmtpHost();
        int port = settings.getSmtpPort() != null ? settings.getSmtpPort() : 587;
        String from = settings.getSmtpSenderAddress();
        String boundary = "----=_LDAPPortal_" + System.currentTimeMillis();

        var socket = new java.net.Socket(host, port);
        socket.setSoTimeout(10000);
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
        var out = new java.io.PrintWriter(socket.getOutputStream(), true);

        try {
            readLine(in);
            out.println("EHLO ldapportal");
            readMultiLine(in);

            if (settings.getSmtpUsername() != null && settings.getSmtpPasswordEncrypted() != null) {
                String password = encryptionService.decrypt(settings.getSmtpPasswordEncrypted());
                String auth = Base64.getEncoder().encodeToString(
                        ("\0" + settings.getSmtpUsername() + "\0" + password).getBytes(StandardCharsets.UTF_8));
                out.println("AUTH PLAIN " + auth);
                readLine(in);
            }

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

    private void sendEmail(String to, String subject, String body) {
        ApplicationSettings settings = appSettingsService.getEntity();
        if (settings.getSmtpHost() == null || settings.getSmtpHost().isBlank()
                || settings.getSmtpSenderAddress() == null || settings.getSmtpSenderAddress().isBlank()) {
            log.info("SMTP not configured — notification logged: to={}, subject={}", to, subject);
            return;
        }

        try {
            sendSmtpEmail(settings, to, subject, body);
        } catch (Exception ex) {
            log.error("Failed to send email to {}: {}", to, ex.getMessage());
        }
    }

    private void sendSmtpEmail(ApplicationSettings settings, String to, String subject, String body) throws Exception {
        String host = settings.getSmtpHost();
        int port = settings.getSmtpPort() != null ? settings.getSmtpPort() : 587;
        String from = settings.getSmtpSenderAddress();

        var socket = new java.net.Socket(host, port);
        socket.setSoTimeout(10000);
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
        var out = new java.io.PrintWriter(socket.getOutputStream(), true);

        try {
            readLine(in);
            out.println("EHLO ldapportal");
            readMultiLine(in);

            if (settings.getSmtpUsername() != null && settings.getSmtpPasswordEncrypted() != null) {
                String password = encryptionService.decrypt(settings.getSmtpPasswordEncrypted());
                String auth = Base64.getEncoder().encodeToString(
                        ("\0" + settings.getSmtpUsername() + "\0" + password).getBytes(StandardCharsets.UTF_8));
                out.println("AUTH PLAIN " + auth);
                readLine(in);
            }

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

    private String readLine(java.io.BufferedReader in) throws Exception {
        String line = in.readLine();
        if (line != null && line.length() >= 3) {
            char c = line.charAt(3);
            if (c == '-') readMultiLine(in);
        }
        return line;
    }

    private void readMultiLine(java.io.BufferedReader in) throws Exception {
        String line;
        do {
            line = in.readLine();
        } while (line != null && line.length() >= 4 && line.charAt(3) == '-');
    }
}
