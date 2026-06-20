// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import com.ldapportal.core.reports.schedule.ScheduledReportJobScheduler;
import com.ldapportal.core.reports.schedule.ScheduledReportJobService;
import com.ldapportal.dto.reports.ReportCatalogueResponse;
import com.ldapportal.dto.reports.ReportJobRequest;
import com.ldapportal.dto.reports.ReportJobResponse;
import com.ldapportal.entity.ScheduledReportJob;
import com.ldapportal.entity.enums.FeatureKey;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD + on-demand execution for scheduled report jobs, plus the schedule-form
 * catalogue. Lives in core (the single scheduler is core-owned); gated by
 * {@link FeatureKey#REPORTS_SCHEDULE}, which is part of the community surface.
 * PDF / S3 / compliance types are rejected by the service's exposure checks
 * (400) in editions that lack the entitlement.
 *
 * <pre>
 *   GET    /report-jobs                      list (paged)
 *   GET    /report-jobs/{jobId}              one
 *   POST   /report-jobs                      create (201)
 *   PUT    /report-jobs/{jobId}              update
 *   DELETE /report-jobs/{jobId}              delete (204)
 *   PATCH  /report-jobs/{jobId}/enabled      enable/disable
 *   POST   /report-jobs/{jobId}/run-now      run asynchronously now (202)
 *   GET    /report-types                     exposed type/format/delivery catalogue
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}")
@RequiredArgsConstructor
public class ReportJobController {

    private final ScheduledReportJobService jobService;
    private final ScheduledReportJobScheduler scheduler;
    private final EntitlementService entitlementService;

    @GetMapping("/report-jobs")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public Page<ReportJobResponse> list(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            Pageable pageable) {
        return jobService.list(directoryId, pageable).map(ReportJobResponse::from);
    }

    @GetMapping("/report-jobs/{jobId}")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ReportJobResponse get(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ReportJobResponse.from(jobService.get(directoryId, jobId));
    }

    @PostMapping("/report-jobs")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ResponseEntity<ReportJobResponse> create(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReportJobRequest req) {
        ScheduledReportJob job = jobService.create(directoryId, req, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportJobResponse.from(job));
    }

    @PutMapping("/report-jobs/{jobId}")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ReportJobResponse update(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReportJobRequest req) {
        return ReportJobResponse.from(jobService.update(directoryId, jobId, req));
    }

    @DeleteMapping("/report-jobs/{jobId}")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ResponseEntity<Void> delete(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        jobService.delete(directoryId, jobId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/report-jobs/{jobId}/enabled")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ReportJobResponse setEnabled(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam boolean enabled) {
        return ReportJobResponse.from(jobService.setEnabled(directoryId, jobId, enabled));
    }

    @PostMapping("/report-jobs/{jobId}/run-now")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ResponseEntity<Map<String, String>> runNow(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ScheduledReportJob job = jobService.get(directoryId, jobId); // 404 if not in this directory
        scheduler.runNowAsync(job);
        return ResponseEntity.accepted().body(Map.of(
                "status", "started",
                "message", "Report job '" + job.getName() + "' execution started"));
    }

    @GetMapping("/report-types")
    @RequiresFeature(FeatureKey.REPORTS_SCHEDULE)
    public ReportCatalogueResponse catalogue(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<ReportCatalogueResponse.TypeOption> types = jobService.exposedReportTypes().stream()
                .map(t -> new ReportCatalogueResponse.TypeOption(t.id(), t.label()))
                .toList();
        List<String> formats = entitlementService.exposed(ReportOutputFormat.class).stream()
                .map(Enum::name).toList();
        List<String> deliveries = entitlementService.exposed(ReportDeliveryMethod.class).stream()
                .map(Enum::name).toList();
        return new ReportCatalogueResponse(types, formats, deliveries);
    }
}
