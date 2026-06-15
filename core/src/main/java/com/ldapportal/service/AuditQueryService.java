// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository auditRepo;

    /**
     * Paginated, multi-filter query. Filters are carried in
     * {@link AuditQueryCriteria} (all optional); the {@code source}
     * field matches the {@code detail.source} key — the per-source
     * convention used by the IVIA verbs, profile changes, etc.
     * ({@code source = "ivia"} narrows to IVIA-only events without the
     * client having to download other events to filter them out).
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> query(AuditQueryCriteria criteria, int page, int size) {
        PageRequest pageable = PageRequest.of(page, clampSize(size));
        return auditRepo.findAll(
                        criteria.directoryId(), criteria.actorId(), actionDbValue(criteria.action()),
                        criteria.targetDn(), criteria.source(), criteria.correlationId(),
                        criteria.from(), criteria.to(), pageable)
                .map(AuditEventResponse::from);
    }

    /**
     * Queries audit events restricted to a set of authorized directories.
     * Used for non-superadmins who haven't specified a directoryId filter.
     * {@link AuditQueryCriteria#directoryId()} is ignored here — the
     * {@code directoryIds} set is the directory scope.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> queryForDirectories(
            Set<UUID> directoryIds, AuditQueryCriteria criteria, int page, int size) {
        PageRequest pageable = PageRequest.of(page, clampSize(size));
        return auditRepo.findAllByDirectoryIds(
                        directoryIds, criteria.actorId(), actionDbValue(criteria.action()),
                        criteria.targetDn(), criteria.source(), criteria.correlationId(),
                        criteria.from(), criteria.to(), pageable)
                .map(AuditEventResponse::from);
    }

    private static String actionDbValue(AuditAction action) {
        return action != null ? action.getDbValue() : null;
    }

    private int clampSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
