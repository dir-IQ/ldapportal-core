// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.Account;
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

import java.util.List;

/**
 * Sends email notifications for approval workflow events. Delegates the actual
 * SMTP send to {@link EmailService}; if SMTP is not configured the notification
 * is logged instead (handled in {@code EmailService}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalNotificationService {

    private final EmailService emailService;
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
     * Notifies {@code target} that another operator just reset their
     * password. Fires from {@link AdminManagementService#resetAdminPassword}
     * and {@link SuperadminManagementService#resetPassword}. Skipped
     * silently for self-resets (the user already knows). Sends to the
     * target's email address — if the row has no email on file, the
     * call falls through to the SMTP-not-configured log line in
     * {@link EmailService}.
     *
     * <p>Without this, an operator-initiated password reset left the
     * target with no out-of-band signal that their credentials had
     * changed; combined with no audit (#12, separate branch), the
     * only trace was a sudden "can't sign in" support ticket.</p>
     */
    @Async
    public void notifyPasswordReset(com.ldapportal.entity.Account target,
                                     com.ldapportal.auth.AuthPrincipal actor) {
        if (target == null || target.getEmail() == null || target.getEmail().isBlank()) {
            log.info("Password-reset notification skipped — no email on file for {}",
                    target != null ? target.getUsername() : "null");
            return;
        }
        String actorName = actor != null && actor.username() != null ? actor.username() : "an operator";
        String subject = "[LDAPPortal] Your password has been reset";
        String body = String.format(
                "Hello %s,\n\n"
                + "Your LDAPPortal account password was reset by %s. Any\n"
                + "active sessions for your account have been signed out.\n\n"
                + "If you did not request this — or if this change is unexpected —\n"
                + "contact your security team immediately.\n\n"
                + "— LDAPPortal",
                target.getDisplayName() != null && !target.getDisplayName().isBlank()
                        ? target.getDisplayName() : target.getUsername(),
                actorName);
        sendEmail(target.getEmail(), subject, body);
    }

    private void sendEmail(String to, String subject, String body) {
        emailService.send(to, subject, body);
    }
}
