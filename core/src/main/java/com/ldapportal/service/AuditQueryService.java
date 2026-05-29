// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository auditRepo;

    /**
     * Paginated, multi-filter query. All filter params are optional.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> query(
            UUID directoryId,
            UUID actorId,
            AuditAction action,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size) {
        return query(directoryId, actorId, action, null, null, from, to, page, size);
    }

    /**
     * Paginated, multi-filter query with a {@code source} discriminator
     * that matches against the {@code detail.source} key — the
     * per-source convention used by the IVIA verbs, profile changes,
     * etc. ({@code source = "ivia"} narrows to IVIA-only events without
     * the client having to download other events to filter them out).
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> query(
            UUID directoryId,
            UUID actorId,
            AuditAction action,
            String targetDn,
            String source,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size) {

        PageRequest pageable = PageRequest.of(page, clampSize(size));
        String actionStr = action != null ? action.getDbValue() : null;
        return auditRepo.findAll(directoryId, actorId, actionStr, targetDn, source, from, to, pageable)
                .map(AuditEventResponse::from);
    }

    /**
     * Queries audit events restricted to a set of authorized directories.
     * Used for non-superadmins who haven't specified a directoryId filter.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> queryForDirectories(
            Set<UUID> directoryIds,
            UUID actorId,
            AuditAction action,
            String targetDn,
            String source,
            OffsetDateTime from,
            OffsetDateTime to,
            int page,
            int size) {

        PageRequest pageable = PageRequest.of(page, clampSize(size));
        String actionStr = action != null ? action.getDbValue() : null;
        return auditRepo.findAllByDirectoryIds(directoryIds, actorId, actionStr, targetDn, source, from, to, pageable)
                .map(AuditEventResponse::from);
    }

    private int clampSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
