// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.dto.reports.ReportJobRequest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ScheduledReportJob;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ScheduledReportJobRepository;
import com.ldapportal.service.EmailService;
import com.ldapportal.service.S3UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + execution for scheduled report jobs. The single core scheduler resolves
 * report content and rendering through the {@code core.reports.schedule} SPIs, so
 * commercial capabilities (compliance types, PDF, S3) plug in without core
 * depending on ee.
 *
 * <p>Edition gating happens at two points (plan §3.5): create/update rejects a
 * non-exposed type/format/delivery, and
 * {@link #runJob(ScheduledReportJob, ReportRunTrigger)} re-checks at run time so a
 * lapsed license can't keep running a gated capability — it records
 * {@code FAILED} instead.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledReportJobService {

    /** Bound on the persisted run-history timeline (newest kept). */
    static final int MAX_RUN_HISTORY = 10;

    private static final DateTimeFormatter SUBJECT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ScheduledReportJobRepository jobRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final List<ScheduledReportContentProvider> contentProviders;
    private final List<ReportRenderer> renderers;
    private final EntitlementService entitlementService;
    private final EmailService emailService;
    private final S3UploadService s3UploadService;
    private final Clock clock;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ScheduledReportJob> list(UUID directoryId, Pageable pageable) {
        return jobRepo.findAllByDirectoryId(directoryId, pageable);
    }

    @Transactional(readOnly = true)
    public ScheduledReportJob get(UUID directoryId, UUID jobId) {
        return jobRepo.findByIdAndDirectoryId(jobId, directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ScheduledReportJob", jobId));
    }

    @Transactional
    public ScheduledReportJob create(UUID directoryId, ReportJobRequest req, AuthPrincipal principal) {
        validate(req);
        ScheduledReportJob job = new ScheduledReportJob();
        job.setDirectoryId(directoryId);
        job.setCreatedByAdminId(principal != null ? principal.id() : null);
        apply(job, req);
        return jobRepo.save(job);
    }

    @Transactional
    public ScheduledReportJob update(UUID directoryId, UUID jobId, ReportJobRequest req) {
        ScheduledReportJob job = get(directoryId, jobId);
        validate(req);
        apply(job, req);
        return jobRepo.save(job);
    }

    @Transactional
    public void delete(UUID directoryId, UUID jobId) {
        jobRepo.delete(get(directoryId, jobId));
    }

    @Transactional
    public ScheduledReportJob setEnabled(UUID directoryId, UUID jobId, boolean enabled) {
        ScheduledReportJob job = get(directoryId, jobId);
        job.setEnabled(enabled);
        return jobRepo.save(job);
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    private void validate(ReportJobRequest req) {
        // cron (normalized to 6-field) must parse
        parseCron(req.cronExpression());

        // timezone, if supplied, must be a valid zone id
        if (req.timezone() != null && !req.timezone().isBlank()) {
            try {
                ZoneId.of(req.timezone().trim());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid timezone: " + req.timezone());
            }
        }

        // report type must resolve to a contributed descriptor and be exposed
        ScheduledReportType type = resolveType(req.reportType())
                .orElseThrow(() -> new IllegalArgumentException("Unknown report type: " + req.reportType()));
        if (!entitlementService.exposes(type)) {
            throw new IllegalArgumentException(
                    "Report type '" + req.reportType() + "' requires entitlement " + type.requiredEntitlement());
        }

        // output format + delivery method gating (PDF / S3 are GOVERNANCE)
        if (!entitlementService.exposes(req.outputFormat())) {
            throw new IllegalArgumentException(req.outputFormat() + " output requires entitlement "
                    + req.outputFormat().requiredEntitlement());
        }
        if (!entitlementService.exposes(req.deliveryMethod())) {
            throw new IllegalArgumentException(req.deliveryMethod() + " delivery requires entitlement "
                    + req.deliveryMethod().requiredEntitlement());
        }

        if (req.deliveryMethod() == ReportDeliveryMethod.EMAIL
                && (req.recipientEmail() == null || req.recipientEmail().isBlank())) {
            throw new IllegalArgumentException("Email delivery requires at least one recipient address");
        }
    }

    private void apply(ScheduledReportJob job, ReportJobRequest req) {
        job.setName(req.name());
        job.setReportType(req.reportType());
        job.setReportParams(req.reportParams());
        job.setCronExpression(normalizeCron(req.cronExpression()));
        job.setOutputFormat(req.outputFormat());
        job.setDeliveryMethod(req.deliveryMethod());
        job.setDeliveryRecipients(req.recipientEmail());
        job.setS3KeyPrefix(req.s3KeyPrefix());
        job.setTimezone(req.timezone() != null && !req.timezone().isBlank() ? req.timezone().trim() : null);
        job.setEnabled(req.enabled());
    }

    /**
     * Accepts a 5-field (unix) or 6-field (Spring) cron and normalizes to the
     * 6-field form Spring's {@link CronExpression} parses, by prepending a
     * {@code "0 "} seconds field. The UI hint is 5-field.
     */
    static String normalizeCron(String cron) {
        String trimmed = cron == null ? "" : cron.trim();
        long fields = Arrays.stream(trimmed.split("\\s+")).filter(s -> !s.isBlank()).count();
        return fields == 5 ? "0 " + trimmed : trimmed;
    }

    private static void parseCron(String cron) {
        try {
            CronExpression.parse(normalizeCron(cron));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cron expression: " + cron);
        }
    }

    Optional<ScheduledReportType> resolveType(String reportType) {
        return contentProviders.stream()
                .flatMap(p -> p.supportedTypes().stream())
                .filter(t -> t.id().equals(reportType))
                .findFirst();
    }

    /**
     * The schedulable report-type descriptors exposed to the current edition —
     * the union of all providers' {@code supportedTypes()} filtered through the
     * entitlement gate, de-duplicated by id. Backs the catalogue endpoint so the
     * schedule form shows operational types in community and all types on
     * commercial without hard-coding.
     */
    @Transactional(readOnly = true)
    public List<ScheduledReportType> exposedReportTypes() {
        Map<String, ScheduledReportType> byId = new LinkedHashMap<>();
        contentProviders.stream()
                .flatMap(p -> p.supportedTypes().stream())
                .filter(entitlementService::exposes)
                .forEach(t -> byId.putIfAbsent(t.id(), t));
        return List.copyOf(byId.values());
    }

    // ── Execution ────────────────────────────────────────────────────────────────

    /**
     * Runs one job end to end: content → render → deliver, then records the
     * outcome. Never throws — any failure (gated capability after a license
     * lapse, missing provider/renderer, delivery error) is recorded as
     * {@code FAILED} so the scheduler poll loop is never broken by one job.
     */
    public void runJob(ScheduledReportJob job, ReportRunTrigger trigger) {
        OffsetDateTime startedAt = now();
        try {
            ScheduledReportType type = resolveType(job.getReportType()).orElse(null);
            if (type == null) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        "Unknown report type: " + job.getReportType(), trigger);
                return;
            }
            // Runtime exposure re-check (license downgrade path).
            if (!entitlementService.exposes(type)) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        "Report type '" + job.getReportType() + "' requires " + type.requiredEntitlement(), trigger);
                return;
            }
            if (!entitlementService.exposes(job.getOutputFormat())) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        job.getOutputFormat() + " output requires " + job.getOutputFormat().requiredEntitlement(), trigger);
                return;
            }
            if (!entitlementService.exposes(job.getDeliveryMethod())) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        job.getDeliveryMethod() + " delivery requires " + job.getDeliveryMethod().requiredEntitlement(), trigger);
                return;
            }

            DirectoryConnection dc = dirRepo.findById(job.getDirectoryId()).orElse(null);
            if (dc == null) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        "Directory not found: " + job.getDirectoryId(), trigger);
                return;
            }

            ScheduledReportContentProvider provider = contentProviders.stream()
                    .filter(p -> p.appliesTo(dc, job.getReportType()))
                    .findFirst().orElse(null);
            if (provider == null) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        "No content provider for report type: " + job.getReportType(), trigger);
                return;
            }

            Map<String, Object> params = job.getReportParams() != null ? job.getReportParams() : Map.of();
            String scopeBaseDn = params.get("scopeBaseDn") != null ? params.get("scopeBaseDn").toString() : null;
            ReportData data = provider.run(dc, job.getReportType(), params, scopeBaseDn);

            ReportRenderer renderer = renderers.stream()
                    .filter(r -> r.supports(job.getOutputFormat()))
                    .findFirst().orElse(null);
            if (renderer == null) {
                recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED,
                        "No renderer for output format: " + job.getOutputFormat(), trigger);
                return;
            }
            RenderedReport rendered = renderer.render(data, new RenderContext(type.label(), now().toInstant()));

            DeliveryOutcome outcome = deliver(job, dc, type.label(), rendered);
            recordResult(job.getId(), startedAt, outcome.status(), outcome.message(), trigger);
        } catch (Exception e) {
            log.error("Scheduled report job '{}' ({}) failed: {}", job.getName(), job.getId(), e.getMessage(), e);
            recordResult(job.getId(), startedAt, ReportJobRunStatus.FAILED, truncate(e.getMessage(), 2000), trigger);
        }
    }

    /** The status + human-readable message a delivery attempt resolves to. */
    private record DeliveryOutcome(ReportJobRunStatus status, String message) {
    }

    /**
     * Delivers the rendered report and reports the real outcome. S3 upload
     * failures throw (caught upstream as FAILED); email delivery is attempted
     * per recipient and the SMTP acceptance of each is aggregated, so a
     * misconfigured or rejecting mailer is recorded honestly rather than as a
     * delivered report.
     */
    private DeliveryOutcome deliver(ScheduledReportJob job, DirectoryConnection dc, String label,
                                    RenderedReport rendered) throws Exception {
        int bytes = rendered.bytes().length;
        if (job.getDeliveryMethod() == ReportDeliveryMethod.S3) {
            String prefix = job.getS3KeyPrefix() != null ? job.getS3KeyPrefix() : "";
            String key = prefix + rendered.filename();
            s3UploadService.upload(key, rendered.bytes(), rendered.contentType());
            return new DeliveryOutcome(ReportJobRunStatus.SUCCESS, "Uploaded " + bytes + " bytes to S3 (" + key + ")");
        }
        // EMAIL
        String subject = reportEmailSubject(label, dc);
        String body = "Attached is the '" + label + "' report for directory '"
                + dc.getDisplayName() + "', generated " + now() + ".";
        List<String> recipients = splitRecipients(job.getDeliveryRecipients());
        int total = recipients.size();
        // No usable recipient address (e.g. a blank/comma-only field that slipped
        // past validation): the report was generated but there was nowhere to send
        // it, so it is not a successful delivery.
        if (total == 0) {
            return new DeliveryOutcome(ReportJobRunStatus.SKIPPED,
                    "Report generated (" + bytes + " bytes) but not delivered — no recipient address configured");
        }
        int sent = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();
        for (String recipient : recipients) {
            EmailService.SendResult res = emailService.sendWithAttachment(recipient, subject, body,
                    rendered.filename(), rendered.contentType(), rendered.bytes());
            switch (res) {
                case SENT -> sent++;
                case SKIPPED -> skipped++;
                case FAILED -> failed.add(recipient);
            }
        }
        // SKIPPED is global (SMTP not configured), so it applies to all recipients.
        if (skipped == total) {
            return new DeliveryOutcome(ReportJobRunStatus.SKIPPED,
                    "Report generated (" + bytes + " bytes) but not delivered — SMTP is not configured");
        }
        if (failed.isEmpty()) {
            return new DeliveryOutcome(ReportJobRunStatus.SUCCESS,
                    "Emailed " + bytes + " bytes to " + sent + "/" + total + " recipient(s)");
        }
        return new DeliveryOutcome(ReportJobRunStatus.FAILED,
                "Emailed " + sent + "/" + total + " recipient(s); delivery failed for: "
                        + truncate(String.join(", ", failed), 500));
    }

    /** Generated subject; the operator never supplies one and it is not persisted. */
    String reportEmailSubject(String label, DirectoryConnection dc) {
        return "[LDAPPortal] " + label + " — " + dc.getDisplayName() + " — " + SUBJECT_DATE.format(now());
    }

    private static List<String> splitRecipients(String recipients) {
        if (recipients == null || recipients.isBlank()) {
            return List.of();
        }
        return Arrays.stream(recipients.split("\\s*,\\s*"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    @Transactional
    public void recordResult(UUID jobId, OffsetDateTime startedAt, ReportJobRunStatus status,
                             String message, ReportRunTrigger trigger) {
        jobRepo.findById(jobId).ifPresent(job -> {
            OffsetDateTime at = now();
            job.setLastRunAt(at);
            job.setLastRunStatus(status);
            job.setLastRunMessage(message);
            List<ReportRunHistoryEntry> history = new ArrayList<>(
                    job.getRunHistory() != null ? job.getRunHistory() : List.of());
            history.add(new ReportRunHistoryEntry(startedAt, at, status, message, trigger));
            while (history.size() > MAX_RUN_HISTORY) {
                history.remove(0);
            }
            job.setRunHistory(history);
            jobRepo.save(job);
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
