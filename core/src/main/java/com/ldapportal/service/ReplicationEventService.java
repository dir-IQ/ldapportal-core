// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReplicationEventResponse;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.ReplicationEventRepository;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read + operator-action surface for the replication event queue.
 * SUPERADMIN only; per-link filtering by status with pagination.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationEventService {

    private final ReplicationEventRepository eventRepo;
    private final AuditService               auditService;

    @Transactional(readOnly = true)
    public Page<ReplicationEventResponse> listForLink(UUID linkId,
                                                       ReplicationEventStatus status,
                                                       int page, int size) {
        // Clamp page size — protects against an operator passing
        // unbounded values that would generate a giant response.
        int clamped = Math.max(1, Math.min(size, 200));
        return eventRepo.findByLinkAndStatus(linkId, status, PageRequest.of(page, clamped))
                .map(ReplicationEventResponse::from);
    }

    @Transactional
    public void retry(AuthPrincipal principal, UUID eventId) {
        int affected = eventRepo.retryByOperator(eventId);
        if (affected == 0) {
            throw new ResourceNotFoundException("ReplicationEvent",
                    eventId + " (or status not retry-eligible)");
        }
        auditService.recordSystemEvent(principal,
                AuditAction.REPLICATION_EVENT_RETRIED_BY_OPERATOR,
                Map.of("eventId", eventId.toString()));
        log.info("Replication event {} re-queued by operator", eventId);
    }

    @Transactional
    public void skip(AuthPrincipal principal, UUID eventId) {
        int affected = eventRepo.skipByOperator(eventId);
        if (affected == 0) {
            throw new ResourceNotFoundException("ReplicationEvent",
                    eventId + " (or status not skip-eligible)");
        }
        auditService.recordSystemEvent(principal,
                AuditAction.REPLICATION_EVENT_SKIPPED_BY_OPERATOR,
                Map.of("eventId", eventId.toString()));
        log.info("Replication event {} skipped by operator", eventId);
    }

    @Transactional
    public void acknowledge(AuthPrincipal principal, UUID eventId) {
        int affected = eventRepo.acknowledgeByOperator(eventId);
        if (affected == 0) {
            throw new ResourceNotFoundException("ReplicationEvent",
                    eventId + " (or status not DEAD_LETTERED)");
        }
        auditService.recordSystemEvent(principal,
                AuditAction.REPLICATION_EVENT_ACKNOWLEDGED,
                Map.of("eventId", eventId.toString()));
        log.info("Replication event {} acknowledged by operator", eventId);
    }
}
