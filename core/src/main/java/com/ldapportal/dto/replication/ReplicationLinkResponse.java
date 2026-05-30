// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;

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
                link.getCreatedAt(),
                link.getUpdatedAt());
    }

    /** Per-link aggregate counts + lag, computed by the service layer. */
    public record LinkHealth(long pendingCount, long failedCount,
                              long deadLetteredCount, OffsetDateTime lastDeliveredAt) {
        public static LinkHealth empty() {
            return new LinkHealth(0L, 0L, 0L, null);
        }
    }
}
