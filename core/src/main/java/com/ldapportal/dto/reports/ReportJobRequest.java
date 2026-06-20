// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.reports;

import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Create/update payload for a scheduled report job. {@code reportType} is a
 * string (built-in {@code OperationalReportType}, addon report id, or ee
 * compliance type), validated in the service against the provider registry.
 * {@code recipientEmail} maps to {@code delivery_recipients}. The email subject
 * is generated at send time, never supplied here.
 */
public record ReportJobRequest(
        @NotBlank String name,
        @NotBlank String reportType,
        Map<String, Object> reportParams,
        @NotBlank String cronExpression,
        @NotNull ReportOutputFormat outputFormat,
        @NotNull ReportDeliveryMethod deliveryMethod,
        String recipientEmail,
        String s3KeyPrefix,
        String timezone,
        boolean enabled) {
}
