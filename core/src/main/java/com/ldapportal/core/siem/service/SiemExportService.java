// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.siem.service;

import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Asynchronously exports audit events to the configured SIEM target.
 * Called by {@link com.ldapportal.service.AuditService} after each event is recorded.
 *
 * <p>Settings are cached with a 30-second TTL to avoid per-event DB queries.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SiemExportService {

    private final ApplicationSettingsService settingsService;
    private final SiemFormatter              formatter;
    private final SiemClient                 client;
    private final AuditEventRepository       auditEventRepo;

    // ── Settings cache (30-second TTL) ──────────────────────────────────────
    private volatile ApplicationSettings cachedSettings;
    private volatile long cacheTimestamp;
    private static final long CACHE_TTL_MS = 30_000;

    private ApplicationSettings getCachedSettings() {
        long now = System.currentTimeMillis();
        if (cachedSettings == null || (now - cacheTimestamp) > CACHE_TTL_MS) {
            cachedSettings = settingsService.getEntity();
            cacheTimestamp = now;
        }
        return cachedSettings;
    }

    /** Invalidate the cached settings (called when settings are updated). */
    public void invalidateCache() {
        cachedSettings = null;
    }

    // ── Real-time export ────────────────────────────────────────────────────

    /**
     * Exports a single audit event to the configured SIEM destination.
     * Runs async — failures are logged but never propagated.
     */
    @Async
    public void export(AuditEvent event) {
        try {
            ApplicationSettings settings = getCachedSettings();
            if (!settings.isSiemEnabled() || settings.getSiemProtocol() == null
                    || settings.getSiemFormat() == null) {
                return;
            }

            String message = formatter.format(event, settings.getSiemFormat());
            client.send(settings, message);
        } catch (Exception e) {
            log.error("SIEM export failed for event [{}]: {}", event.getId(), e.getMessage());
        }
    }

    // ── Backfill (historical export) ────────────────────────────────────────

    /**
     * Exports historical audit events from a date range to the configured SIEM.
     * Processes events in pages to avoid loading everything into memory.
     *
     * @return number of events exported
     */
    public int backfill(OffsetDateTime from, OffsetDateTime to) {
        ApplicationSettings settings = settingsService.getEntity();
        if (!settings.isSiemEnabled() || settings.getSiemProtocol() == null
                || settings.getSiemFormat() == null) {
            throw new IllegalStateException("SIEM is not fully configured");
        }

        int pageSize = 500;
        int exported = 0;
        int pageNum = 0;

        Page<AuditEvent> page;
        do {
            page = auditEventRepo.findAll(
                    null, null, null, null, null, from, to,
                    PageRequest.of(pageNum, pageSize, Sort.by("occurred_at").ascending()));

            for (AuditEvent event : page.getContent()) {
                try {
                    String message = formatter.format(event, settings.getSiemFormat());
                    client.send(settings, message);
                    exported++;
                } catch (Exception e) {
                    log.error("SIEM backfill failed for event [{}]: {}", event.getId(), e.getMessage());
                }
            }

            pageNum++;
            log.info("SIEM backfill progress: exported {} of {} events", exported, page.getTotalElements());
        } while (page.hasNext());

        log.info("SIEM backfill complete: {} events exported from {} to {}", exported, from, to);
        return exported;
    }

    // ── Test utilities ──────────────────────────────────────────────────────

    /**
     * Sends a test event to verify SIEM connectivity.
     */
    public String sendTestEvent() {
        ApplicationSettings settings = settingsService.getEntity();
        if (!settings.isSiemEnabled()) {
            return "SIEM export is not enabled.";
        }
        if (settings.getSiemProtocol() == null || settings.getSiemFormat() == null) {
            return "SIEM protocol or format is not configured.";
        }

        AuditEvent testEvent = AuditEvent.builder()
                .id(UUID.randomUUID())
                .source(AuditSource.INTERNAL)
                .action(AuditAction.INTEGRITY_CHECK)
                .actorUsername("system")
                .actorType("SUPERADMIN")
                .targetDn("cn=siem-test,dc=example,dc=com")
                .detail(java.util.Map.of("type", "siem_connectivity_test"))
                .occurredAt(OffsetDateTime.now())
                .build();

        try {
            String message = formatter.format(testEvent, settings.getSiemFormat());
            client.send(settings, message);
            return "Test event sent successfully.";
        } catch (Exception e) {
            return "Test event failed: " + e.getMessage();
        }
    }

    /**
     * Tests network connectivity to the configured SIEM target without sending events.
     */
    public String testConnectivity() {
        ApplicationSettings settings = settingsService.getEntity();
        return client.testConnectivity(settings);
    }
}
