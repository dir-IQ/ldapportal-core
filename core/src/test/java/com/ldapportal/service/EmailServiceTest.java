// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the SMTP-not-configured guard, the only EmailService path testable
 * without a live SMTP server: both sends must log-and-skip (never throw) and not
 * touch the encryption service. The socket protocol is exercised end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private ApplicationSettingsService appSettingsService;
    @Mock private EncryptionService encryptionService;
    @InjectMocks private EmailService emailService;

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
}
