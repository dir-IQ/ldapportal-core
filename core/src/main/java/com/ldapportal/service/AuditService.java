// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.audit.AuditDetailContributor;
import com.ldapportal.core.events.AuditRecordedEvent;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.core.siem.service.SiemExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records audit events produced by LDAP write operations.
 *
 * <p>All public methods are {@link Async} so callers (typically
 * {@link LdapOperationService}) are not blocked by the DB write.
 * Each call runs in its own transaction so a recording failure never
 * rolls back the original LDAP operation.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository         auditRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final SiemExportService             siemExportService;
    private final ApplicationEventPublisher     eventPublisher;
    /**
     * SPI contributors that fold extra keys into {@code detail}
     * before persist. Spring injects an empty list when nothing
     * is registered, which is the community-distribution shape;
     * the ISVA addon contributes here in
     * {@code community-plus-isva} / {@code commercial}.
     */
    private final List<AuditDetailContributor> detailContributors;

    // ── Internal-event recording ──────────────────────────────────────────────

    /**
     * Records an audit event asynchronously after a successful write op.
     *
     * @param principal   the acting admin
     * @param directoryId the directory that was modified
     * @param action      what was done
     * @param targetDn    the entry DN that was affected
     * @param detail      optional extra detail (attribute names, values, etc.)
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthPrincipal principal,
                       UUID directoryId,
                       AuditAction action,
                       String targetDn,
                       Map<String, Object> detail) {
        try {
            DirectoryConnection dir = dirRepo.findById(directoryId).orElse(null);
            String dirName = dir != null ? dir.getDisplayName() : null;

            Map<String, Object> enrichedDetail = enrichDetail(directoryId, action, targetDn, detail);

            AuditEvent event = AuditEvent.builder()
                    .source(AuditSource.INTERNAL)
                    .actorId(principal.id())
                    .actorType(principal.type().name())
                    .actorUsername(principal.username())
                    .directoryId(directoryId)
                    .directoryName(dirName)
                    .action(action)
                    .targetDn(targetDn)
                    .detail(enrichedDetail)
                    .correlationId(currentCorrelation())
                    .occurredAt(OffsetDateTime.now())
                    .build();

            auditRepo.save(event);
            eventPublisher.publishEvent(new AuditRecordedEvent(event));
            siemExportService.export(event);
        } catch (Exception ex) {
            // Never let audit failures bubble up to callers.
            log.error("Failed to record audit event [action={}, dn={}, actor={}]: {}",
                    action, targetDn, principal.username(), ex.getMessage(), ex);
        }
    }

    // ── Changelog-event recording (called from LdapChangelogReader) ───────────

    /**
     * Persists a single changelog-sourced audit event.
     * Called synchronously from within the poller's own transaction.
     *
     * @param directoryId     target directory (may be {@code null})
     * @param directoryName   denormalised name (may be {@code null})
     * @param targetDn        the entry's DN from the changelog
     * @param changeNumber    the {@code changeNumber} attribute value
     * @param changeDetail    all raw attributes from the changelog entry
     * @param occurredAt      timestamp of the change
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChangelogEvent(UUID directoryId,
                                     String directoryName,
                                     String targetDn,
                                     String changeNumber,
                                     Map<String, Object> changeDetail,
                                     OffsetDateTime occurredAt) {
        try {
            if (auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber)) {
                return;  // already recorded (idempotency guard)
            }

            AuditEvent event = AuditEvent.builder()
                    .source(AuditSource.LDAP_CHANGELOG)
                    .directoryId(directoryId)
                    .directoryName(directoryName)
                    .action(AuditAction.LDAP_CHANGE)
                    .targetDn(targetDn)
                    .detail(changeDetail)
                    .changelogChangeNumber(changeNumber)
                    .occurredAt(occurredAt)
                    .build();

            auditRepo.save(event);
            eventPublisher.publishEvent(new AuditRecordedEvent(event));
            siemExportService.export(event);
        } catch (Exception ex) {
            log.error("Failed to record changelog event [changeNumber={}, dn={}]: {}",
                    changeNumber, targetDn, ex.getMessage(), ex);
        }
    }

    // ── System-level events (non-directory) ───────────────────────────────────

    /**
     * Record a system-level audit event with no actor — used for
     * background-worker transitions where no human or token initiated
     * the change. Currently used by the directory-sync worker to
     * record REPLICATION_EVENT_DEAD_LETTERED. Distinct from
     * {@link #recordSystemEvent(AuthPrincipal, AuditAction, Map)} so
     * the actor-required contract there stays explicit.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystemEventNoActor(AuditAction action,
                                          Map<String, Object> detail) {
        try {
            AuditEvent event = AuditEvent.builder()
                    .source(AuditSource.INTERNAL)
                    .action(action)
                    .detail(detail)
                    .correlationId(currentCorrelation())
                    .occurredAt(OffsetDateTime.now())
                    .build();
            auditRepo.save(event);
            eventPublisher.publishEvent(new AuditRecordedEvent(event));
            siemExportService.export(event);
        } catch (Exception ex) {
            log.error("Failed to record actor-less audit event [action={}]: {}",
                    action, ex.getMessage(), ex);
        }
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystemEvent(AuthPrincipal principal,
                                  AuditAction action,
                                  Map<String, Object> detail) {
        try {
            AuditEvent event = AuditEvent.builder()
                    .source(AuditSource.INTERNAL)
                    .actorId(principal.id())
                    .actorType(principal.type().name())
                    .actorUsername(principal.username())
                    .action(action)
                    .detail(detail)
                    .correlationId(currentCorrelation())
                    .occurredAt(OffsetDateTime.now())
                    .build();

            auditRepo.save(event);
            eventPublisher.publishEvent(new AuditRecordedEvent(event));
            siemExportService.export(event);
        } catch (Exception ex) {
            log.error("Failed to record system audit event [action={}, actor={}]: {}",
                    action, principal.username(), ex.getMessage(), ex);
        }
    }

    /**
     * The correlation id to stamp on an audit row: the active
     * {@link CorrelationContext} scope (set by the request filter or a
     * scheduler/worker scope, and propagated onto {@code @Async} threads by
     * the task decorator), or null when no scope is active.
     */
    private static UUID currentCorrelation() {
        return CorrelationContext.current().orElse(null);
    }

    // ── Detail enrichment ─────────────────────────────────────────────────────

    /**
     * Run every {@link AuditDetailContributor} and merge their
     * contributions into the caller-supplied detail map.
     *
     * <p>Contributor output wins on key collision (so an addon can
     * deliberately override a caller-supplied value when needed — rare,
     * but the addon is the more domain-aware party). Contributor
     * exceptions are caught per-contributor and logged so one broken
     * addon doesn't kill the whole audit pipeline.</p>
     *
     * <p>Returns the original {@code base} reference when no
     * contributor produced anything, to keep the no-addon path
     * allocation-free.</p>
     */
    private Map<String, Object> enrichDetail(UUID directoryId,
                                             AuditAction action,
                                             String targetDn,
                                             Map<String, Object> base) {
        if (detailContributors == null || detailContributors.isEmpty()) {
            return base;
        }

        Map<String, Object> merged = null;
        for (AuditDetailContributor contributor : detailContributors) {
            try {
                Map<String, Object> extra = contributor.contribute(directoryId, action, targetDn, base);
                if (extra == null || extra.isEmpty()) continue;
                if (merged == null) {
                    merged = new HashMap<>();
                    if (base != null) merged.putAll(base);
                }
                merged.putAll(extra);
            } catch (Exception ex) {
                log.warn("Audit detail contributor {} threw — skipping its contribution: {}",
                        contributor.getClass().getName(), ex.getMessage());
            }
        }
        return merged != null ? merged : base;
    }

    // ── Read helpers (used by changelog reader) ───────────────────────────────

    @Transactional(readOnly = true)
    public boolean isChangelogEventRecorded(UUID directoryId, String changeNumber) {
        return auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber);
    }
}
