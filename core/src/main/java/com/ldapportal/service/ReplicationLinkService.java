// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.dto.replication.ReplicationLinkResponse.LinkHealth;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for replication links. Exposed via
 * {@code /api/v1/superadmin/replication-links/*}; SUPERADMIN only.
 *
 * <p>Health counts (pending / failed / dead-lettered / last-delivered)
 * are computed in a single batched query against
 * {@link ReplicationEventRepository#findHealthRollup} rather than
 * triggering one query per link.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationLinkService {

    private final ReplicationLinkRepository  linkRepo;
    private final ReplicationEventRepository eventRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final AuditService               auditService;

    @Transactional(readOnly = true)
    public List<ReplicationLinkResponse> listLinks() {
        List<ReplicationLink> links = linkRepo.findAll();
        if (links.isEmpty()) return List.of();
        Map<UUID, LinkHealth> health = healthByLinkId(links);
        return links.stream()
                .map(l -> ReplicationLinkResponse.from(l, health.getOrDefault(l.getId(), LinkHealth.empty())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplicationLinkResponse getLink(UUID id) {
        ReplicationLink link = require(id);
        LinkHealth health = healthByLinkId(List.of(link)).getOrDefault(id, LinkHealth.empty());
        return ReplicationLinkResponse.from(link, health);
    }

    @Transactional
    public ReplicationLinkResponse createLink(AuthPrincipal principal, ReplicationLinkRequest req) {
        validateRequest(req, null);
        ReplicationLink link = new ReplicationLink();
        applyRequest(link, req);
        link = linkRepo.save(link);
        log.info("Replication link created: {} ({} → {})",
                link.getId(), link.getSourceDirectory().getDisplayName(),
                link.getTargetDirectory().getDisplayName());
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_CREATED, auditDetail(link));
        return ReplicationLinkResponse.from(link, LinkHealth.empty());
    }

    @Transactional
    public ReplicationLinkResponse updateLink(AuthPrincipal principal, UUID id, ReplicationLinkRequest req) {
        ReplicationLink link = require(id);
        boolean wasEnabled = link.isEnabled();
        validateRequest(req, id);
        applyRequest(link, req);
        link = linkRepo.save(link);
        Map<UUID, LinkHealth> health = healthByLinkId(List.of(link));

        // Always record the general update first; if the enabled flag
        // also flipped, follow with the specific ENABLED / DISABLED
        // action so the audit log carries both signals. Operators
        // reviewing 'who turned this off' shouldn't have to read the
        // generic UPDATE detail map to find the answer.
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_UPDATED, auditDetail(link));
        if (wasEnabled != link.isEnabled()) {
            AuditAction toggle = link.isEnabled()
                    ? AuditAction.REPLICATION_LINK_ENABLED
                    : AuditAction.REPLICATION_LINK_DISABLED;
            auditService.recordSystemEvent(principal, toggle, auditDetail(link));
        }

        return ReplicationLinkResponse.from(link, health.getOrDefault(id, LinkHealth.empty()));
    }

    @Transactional
    public void deleteLink(AuthPrincipal principal, UUID id) {
        ReplicationLink link = require(id);
        Map<String, Object> detail = auditDetail(link);
        linkRepo.delete(link);
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_DELETED, detail);
        log.info("Replication link deleted: {}", id);
    }

    private static Map<String, Object> auditDetail(ReplicationLink link) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("linkId",              link.getId().toString());
        detail.put("displayName",         link.getDisplayName());
        detail.put("sourceDirectoryId",   link.getSourceDirectory().getId().toString());
        detail.put("sourceDirectoryName", link.getSourceDirectory().getDisplayName());
        detail.put("targetDirectoryId",   link.getTargetDirectory().getId().toString());
        detail.put("targetDirectoryName", link.getTargetDirectory().getDisplayName());
        detail.put("enabled",             link.isEnabled());
        return detail;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReplicationLink require(UUID id) {
        return linkRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ReplicationLink", id.toString()));
    }

    private void validateRequest(ReplicationLinkRequest req, UUID updatingId) {
        if (req.sourceDirectoryId().equals(req.targetDirectoryId())) {
            throw new IllegalArgumentException(
                    "sourceDirectoryId and targetDirectoryId must differ");
        }
        // Both base DNs set OR both unset — mirrors the DB CHECK
        // constraint. Catching it here gives a 400 with a useful
        // message instead of a 500 with a constraint-violation
        // stack trace.
        boolean sourceSet = req.sourceBaseDn() != null && !req.sourceBaseDn().isBlank();
        boolean targetSet = req.targetBaseDn() != null && !req.targetBaseDn().isBlank();
        if (sourceSet != targetSet) {
            throw new IllegalArgumentException(
                    "sourceBaseDn and targetBaseDn must both be set or both null");
        }
    }

    private void applyRequest(ReplicationLink link, ReplicationLinkRequest req) {
        link.setDisplayName(req.displayName());
        link.setSourceDirectory(requireDirectory(req.sourceDirectoryId()));
        link.setTargetDirectory(requireDirectory(req.targetDirectoryId()));
        link.setSourceBaseDn(blankToNull(req.sourceBaseDn()));
        link.setTargetBaseDn(blankToNull(req.targetBaseDn()));
        link.setEnabled(req.enabled());
        link.setAutoCreateOnMissing(req.autoCreateOnMissing());

        // Attribute mappings: replace the whole list. orphanRemoval=true
        // on the @OneToMany makes JPA clean up removed rows.
        link.getAttributeMappings().clear();
        if (req.attributeMappings() != null) {
            for (var rule : req.attributeMappings()) {
                ReplicationLinkAttrMapping m = new ReplicationLinkAttrMapping();
                m.setLink(link);
                m.setSourceAttr(rule.sourceAttr());
                m.setTargetAttr(rule.targetAttr());
                m.setValueTemplate(blankToNull(rule.valueTemplate()));
                link.getAttributeMappings().add(m);
            }
        }
    }

    private DirectoryConnection requireDirectory(UUID id) {
        return dirRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", id.toString()));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Map link ids → {@link LinkHealth}. Returns empty for any link
     * without events; the caller defaults to {@code LinkHealth.empty()}.
     */
    private Map<UUID, LinkHealth> healthByLinkId(List<ReplicationLink> links) {
        if (links.isEmpty()) return Map.of();
        List<UUID> ids = links.stream().map(ReplicationLink::getId).toList();
        List<Object[]> rows = eventRepo.findHealthRollup(ids);
        Map<UUID, LinkHealth> result = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            UUID linkId = (UUID) row[0];
            long pending      = ((Number) row[1]).longValue();
            long failed       = ((Number) row[2]).longValue();
            long deadLettered = ((Number) row[3]).longValue();
            OffsetDateTime lastDelivered = (OffsetDateTime) row[4];
            result.put(linkId, new LinkHealth(pending, failed, deadLettered, lastDelivered));
        }
        return result;
    }
}
