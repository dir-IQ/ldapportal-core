// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Audit log query endpoint.
 *
 * <pre>
 *   GET /api/v1/audit — paginated, filterable audit log
 * </pre>
 *
 * <p>All filter parameters are optional.  Results are paginated and ordered
 * by {@code occurredAt DESC}.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditQueryService queryService;
    private final PermissionService permissionService;

    /**
     * Returns audit events with optional filters.
     *
     * <p>Non-superadmins can only query directories they have profile access to.
     * If no directoryId filter is provided, results are restricted to the admin's
     * authorized directories.</p>
     *
     * @param directoryId filter by directory (optional)
     * @param actorId     filter by admin actor UUID (optional)
     * @param action      filter by action (optional)
     * @param from        lower bound on {@code occurredAt} (ISO-8601, optional)
     * @param to          upper bound on {@code occurredAt} (ISO-8601, optional)
     * @param page        zero-based page number (default 0)
     * @param size        page size, 1–200 (default 50)
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public Page<AuditEventResponse> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) UUID directoryId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String targetDn,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) UUID correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        AuditQueryCriteria criteria = AuditQueryCriteria.builder()
                .directoryId(directoryId).actorId(actorId).action(action)
                .targetDn(targetDn).source(source).correlationId(correlationId)
                .from(from).to(to).build();

        // Non-superadmins can only query their authorized directories
        Set<UUID> authorizedDirs = permissionService.getAuthorizedDirectoryIds(principal);
        if (!authorizedDirs.isEmpty()) {
            if (directoryId != null && !authorizedDirs.contains(directoryId)) {
                throw new AccessDeniedException(
                        "No access to audit logs for directory [" + directoryId + "]");
            }
            // targetDn filter must also be inside the admin's profile
            // OU scope. Without this, an admin authorized for one OU
            // of an authorized directory could probe arbitrary DNs in
            // sibling OUs by passing them as targetDn — confirming the
            // entry exists and reading any audit detail recorded
            // against it.
            if (targetDn != null && !targetDn.isBlank()) {
                if (directoryId != null) {
                    permissionService.requireDnWithinScope(principal, directoryId, targetDn);
                } else {
                    boolean anyAllow = false;
                    for (UUID authorizedDir : authorizedDirs) {
                        if (permissionService.isDnWithinScope(principal, authorizedDir, targetDn)) {
                            anyAllow = true;
                            break;
                        }
                    }
                    if (!anyAllow) {
                        throw new AccessDeniedException(
                                "targetDn [" + targetDn + "] is not within any authorized profile scope");
                    }
                }
            }
            if (directoryId == null) {
                // Query each authorized directory and merge — or require directoryId
                // For now, require non-superadmins to specify a directoryId filter
                return queryService.queryForDirectories(authorizedDirs, criteria, page, size);
            }
        }

        return queryService.query(criteria, page, size);
    }
}
