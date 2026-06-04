// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReplicationCaptureMode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of a replication link plus its current health.
 * {@code pendingCount} / {@code failedCount} / {@code deadLetteredCount}
 * / {@code lastDeliveredAt} are derived per-link from {@code replication_events};
 * the service computes them in a single batched query before assembling
 * the response.
 */
public record ReplicationLinkResponse(
        UUID id,
        String displayName,
        UUID sourceDirectoryId,
        String sourceDirectoryName,
        UUID targetDirectoryId,
        String targetDirectoryName,
        String sourceBaseDn,
        String targetBaseDn,
        boolean enabled,
        boolean autoCreateOnMissing,
        List<AttributeMappingItem> attributeMappings,
        long pendingCount,
        long failedCount,
        long deadLetteredCount,
        OffsetDateTime lastDeliveredAt,
        boolean reconcileEnabled,
        ReconcileMode reconcileMode,
        OffsetDateTime reconcileFirstRunAt,
        Integer reconcileIntervalSecs,
        ReconcileDeleteAction reconcileDeleteAction,
        OffsetDateTime reconcileNextRunAt,
        OffsetDateTime reconcileLastRunAt,
        long openFindingCount,
        // ── Changelog capture config + read-only health surface (§7A.7) ──
        ReplicationCaptureMode captureMode,
        ChangelogFormat changelogFormat,
        String changelogBaseDn,
        String excludeFilter,
        Long changelogLastChangeNumber,
        Long changelogSourceLastChangeNumber,
        /** Un-replicated source changes = source head − cursor; null until both are known. */
        Long changelogLag,
        ChangelogHealth changelogHealth,
        OffsetDateTime changelogLastPolledAt,
        String changelogLastError,
        OffsetDateTime changelogLastErrorAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record AttributeMappingItem(String sourceAttr, String targetAttr, String valueTemplate) {
        public static AttributeMappingItem from(ReplicationLinkAttrMapping m) {
            return new AttributeMappingItem(m.getSourceAttr(), m.getTargetAttr(), m.getValueTemplate());
        }
    }

    /**
     * Build a response from a link entity plus pre-computed counts.
     * Splitting computation from materialization lets the service do
     * the COUNT(...) FILTER(WHERE status=...) GROUP BY link_id query
     * once and attach results, rather than triggering a query per link.
     */
    public static ReplicationLinkResponse from(ReplicationLink link, LinkHealth health) {
        return new ReplicationLinkResponse(
                link.getId(),
                link.getDisplayName(),
                link.getSourceDirectory().getId(),
                link.getSourceDirectory().getDisplayName(),
                link.getTargetDirectory().getId(),
                link.getTargetDirectory().getDisplayName(),
                link.getSourceBaseDn(),
                link.getTargetBaseDn(),
                link.isEnabled(),
                link.isAutoCreateOnMissing(),
                link.getAttributeMappings().stream().map(AttributeMappingItem::from).toList(),
                health.pendingCount(),
                health.failedCount(),
                health.deadLetteredCount(),
                health.lastDeliveredAt(),
                link.isReconcileEnabled(),
                link.getReconcileMode(),
                link.getReconcileFirstRunAt(),
                link.getReconcileIntervalSecs(),
                link.getReconcileDeleteAction(),
                link.getReconcileNextRunAt(),
                link.getReconcileLastRunAt(),
                health.openFindingCount(),
                link.getCaptureMode(),
                link.getChangelogFormat(),
                link.getChangelogBaseDn(),
                link.getExcludeFilter(),
                link.getChangelogLastChangeNumber(),
                link.getChangelogSourceLastChangeNumber(),
                lag(link),
                link.getChangelogHealth(),
                link.getChangelogLastPolledAt(),
                link.getChangelogLastError(),
                link.getChangelogLastErrorAt(),
                link.getCreatedAt(),
                link.getUpdatedAt());
    }

    /** Lag = source head − cursor, or null until both ends are known. */
    private static Long lag(ReplicationLink link) {
        Long head = link.getChangelogSourceLastChangeNumber();
        Long cursor = link.getChangelogLastChangeNumber();
        if (head == null || cursor == null) return null;
        return Math.max(0L, head - cursor);
    }

    /** Per-link aggregate counts + lag, computed by the service layer. */
    public record LinkHealth(long pendingCount, long failedCount,
                              long deadLetteredCount, OffsetDateTime lastDeliveredAt,
                              long openFindingCount) {
        public static LinkHealth empty() {
            return new LinkHealth(0L, 0L, 0L, null, 0L);
        }
    }
}
