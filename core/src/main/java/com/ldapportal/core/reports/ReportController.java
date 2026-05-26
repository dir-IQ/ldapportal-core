// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.util.CsvUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * On-demand execution of operational reports — the directory-metrics
 * and integrity reports that ship in both editions. Lives in core so
 * the community distribution can run these without the GOVERNANCE
 * entitlement.
 *
 * <pre>
 *   POST /api/v1/directories/{directoryId}/reports/run-data
 *       Run an operational report; returns ReportData JSON for inline display.
 *
 *   POST /api/v1/directories/{directoryId}/reports/run?format=CSV
 *       Run an operational report; returns CSV bytes for download.
 *       PDF output for operational reports lives in ee/governance;
 *       requesting format=PDF here returns 400.
 * </pre>
 *
 * <p>Compliance reports (access reviews, SoD, drift, termination
 * velocity, audit-log exports, privileged-account inventory) and
 * scheduled-job CRUD live in {@code ee/governance} under separate
 * URL prefixes. See {@code docs/edition-boundary.md}.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}")
@RequiredArgsConstructor
public class ReportController {

    private final OperationalReportService        reportService;
    private final DirectoryConnectionRepository   dirRepo;
    private final ApiRateLimiter                  rateLimiter;

    /**
     * Runs an operational report and returns the structured data as
     * JSON for inline display.
     */
    @PostMapping("/reports/run-data")
    @RequiresFeature(FeatureKey.REPORTS_RUN)
    @Transactional(readOnly = true)
    public ReportData runData(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RunOperationalReportRequest req) {

        rateLimiter.check(principal.username(), "report-run");
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
        return reportService.run(dc, req.reportType(), req.reportParams(), directoryId);
    }

    /**
     * Runs an operational report and returns CSV bytes for download.
     * PDF output is not supported here — it ships in {@code ee/governance}
     * because the PDF renderer (with branding, signatures, framework
     * templates) is part of the commercial bundle.
     */
    @PostMapping("/reports/run")
    @RequiresFeature(FeatureKey.REPORTS_RUN)
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> run(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RunOperationalReportRequest req,
            @RequestParam(name = "format", defaultValue = "CSV") String format) {

        if (!"CSV".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException(
                    "Operational reports support CSV output only. "
                    + "PDF output requires the commercial governance module.");
        }

        rateLimiter.check(principal.username(), "report-run");
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));

        ReportData data = reportService.run(dc, req.reportType(), req.reportParams(), directoryId);
        byte[] csv = CsvUtils.write(data.columns(), data.rows());

        String filename = req.reportType().name().toLowerCase() + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }
}
