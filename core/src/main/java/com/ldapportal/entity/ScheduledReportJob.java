// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import com.ldapportal.core.reports.schedule.ReportRunHistoryEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A scheduled report job, persisted in {@code scheduled_report_jobs} (defined in
 * the core baseline). Core owns the single scheduler; commercial capabilities
 * (compliance report types, PDF output, S3 delivery) plug in through the
 * {@code core.reports.schedule} SPIs rather than a parallel ee scheduler.
 *
 * <p>{@code reportType} is a free-form string (not an enum) because the
 * schedulable type set is dynamic — built-in {@code OperationalReportType}s,
 * addon {@code OperationalReportProvider} ids, and ee compliance types — so the
 * baseline {@code chk_report_type} CHECK is dropped (migration V19) and the type
 * is validated in the service against the provider registry.</p>
 *
 * <p>{@code timezone} and {@code run_history} give parity with the commercial
 * scheduler: cron is evaluated in the job's IANA zone (null = UTC), and each run
 * appends a bounded {@link ReportRunHistoryEntry} timeline alongside the
 * {@code lastRun*} scalars.</p>
 */
@Entity
@Table(name = "scheduled_report_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ScheduledReportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "directory_id", nullable = false)
    private UUID directoryId;

    @Column(nullable = false)
    private String name;

    /** Built-in OperationalReportType name, addon report id, or ee compliance type. */
    @Column(name = "report_type", nullable = false, length = 50)
    private String reportType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_params", columnDefinition = "jsonb")
    private Map<String, Object> reportParams;

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 10)
    private ReportOutputFormat outputFormat = ReportOutputFormat.CSV;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 10)
    private ReportDeliveryMethod deliveryMethod = ReportDeliveryMethod.EMAIL;

    /** Comma-separated recipient addresses (EMAIL delivery). */
    @Column(name = "delivery_recipients", columnDefinition = "text")
    private String deliveryRecipients;

    @Column(name = "s3_key_prefix", length = 500)
    private String s3KeyPrefix;

    /** IANA zone id for cron evaluation; {@code null} = UTC. */
    @Column(name = "timezone", length = 50)
    private String timezone;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 50)
    private ReportJobRunStatus lastRunStatus;

    @Column(name = "last_run_message", columnDefinition = "text")
    private String lastRunMessage;

    /** Bounded run timeline (newest last); never null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "run_history", columnDefinition = "jsonb", nullable = false)
    private List<ReportRunHistoryEntry> runHistory = new ArrayList<>();

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
