// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.reports;

import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import com.ldapportal.core.reports.schedule.ReportRunHistoryEntry;
import com.ldapportal.entity.ScheduledReportJob;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read shape for a scheduled report job. {@code recipientEmail} mirrors the
 * stored {@code delivery_recipients}; the email subject is generated at send
 * time and never stored, so it is not surfaced here.
 */
public record ReportJobResponse(
        UUID id,
        UUID directoryId,
        String name,
        String reportType,
        Map<String, Object> reportParams,
        String cronExpression,
        ReportOutputFormat outputFormat,
        ReportDeliveryMethod deliveryMethod,
        String recipientEmail,
        String s3KeyPrefix,
        String timezone,
        boolean enabled,
        OffsetDateTime lastRunAt,
        ReportJobRunStatus lastRunStatus,
        String lastRunMessage,
        List<ReportRunHistoryEntry> runHistory,
        UUID createdByAdminId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ReportJobResponse from(ScheduledReportJob j) {
        return new ReportJobResponse(
                j.getId(),
                j.getDirectoryId(),
                j.getName(),
                j.getReportType(),
                j.getReportParams(),
                j.getCronExpression(),
                j.getOutputFormat(),
                j.getDeliveryMethod(),
                j.getDeliveryRecipients(),
                j.getS3KeyPrefix(),
                j.getTimezone(),
                j.isEnabled(),
                j.getLastRunAt(),
                j.getLastRunStatus(),
                j.getLastRunMessage(),
                j.getRunHistory(),
                j.getCreatedByAdminId(),
                j.getCreatedAt(),
                j.getUpdatedAt());
    }
}
