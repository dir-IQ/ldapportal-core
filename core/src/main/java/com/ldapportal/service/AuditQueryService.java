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

import java.util.List;
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
                        criteria.directoryId(), criteria.actorId(), actionNames(criteria.actions()),
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
                        directoryIds, criteria.actorId(), actionNames(criteria.actions()),
                        criteria.targetDn(), criteria.source(), criteria.correlationId(),
                        criteria.from(), criteria.to(), pageable)
                .map(AuditEventResponse::from);
    }

    /**
     * Renders the optional action filter as a comma-joined list of audit-action
     * names (e.g. {@code "USER_CREATE,USER_UPDATE"}) for the repository's
     * {@code string_to_array(...) = ANY} match, or {@code null} when no action
     * filter is set.
     *
     * <p><strong>Names, not dbValues.</strong> {@code AuditEvent.action} is mapped
     * {@code @Enumerated(EnumType.STRING)}, which takes precedence over the
     * autoApply {@code AuditActionConverter} (JPA does not apply converters to
     * {@code @Enumerated} attributes). The persisted column therefore holds the
     * enum {@link Enum#name()} ({@code "USER_CREATE"}), not the
     * {@code getDbValue()} dot-notation ({@code "user.create"}). Filtering on
     * dbValues silently matched nothing — this must match on names. Enum names
     * never contain commas, so a comma delimiter is unambiguous.
     */
    private static String actionNames(List<AuditAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.stream()
                .filter(java.util.Objects::nonNull)
                .map(AuditAction::name)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private int clampSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
