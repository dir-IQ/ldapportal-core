// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.enums.OutboundEventType;
import com.ldapportal.entity.enums.AuditAction;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The wire-contract chokepoint. Maps internal {@link AuditAction} values to
 * externally-exposed {@link OutboundEventType} values. Unmapped actions
 * return {@link Optional#empty()} and are silently skipped by the bridge —
 * only actions present in the map are ever forwarded over the wire.
 *
 * <p>Adding a new forwardable action: add the map entry here AND a matching
 * enum value to {@code OutboundEventType}. See the {@code everyOutboundTypeIsProducible}
 * test for the guard that enforces the symmetry.</p>
 */
@Component
public class AuditActionToOutboundTypeMap {

    private final Map<AuditAction, OutboundEventType> map;

    public AuditActionToOutboundTypeMap() {
        Map<AuditAction, OutboundEventType> m = new EnumMap<>(AuditAction.class);

        // User lifecycle
        m.put(AuditAction.USER_CREATE,  OutboundEventType.USER_CREATED);
        m.put(AuditAction.USER_UPDATE,  OutboundEventType.USER_UPDATED);
        m.put(AuditAction.USER_DELETE,  OutboundEventType.USER_DELETED);
        m.put(AuditAction.USER_ENABLE,  OutboundEventType.USER_ENABLED);
        m.put(AuditAction.USER_DISABLE, OutboundEventType.USER_DISABLED);
        m.put(AuditAction.USER_MOVE,    OutboundEventType.USER_MOVED);

        // Group lifecycle
        m.put(AuditAction.GROUP_CREATE,         OutboundEventType.GROUP_CREATED);
        m.put(AuditAction.GROUP_UPDATE,         OutboundEventType.GROUP_UPDATED);
        m.put(AuditAction.GROUP_DELETE,         OutboundEventType.GROUP_DELETED);
        m.put(AuditAction.GROUP_MEMBER_ADD,     OutboundEventType.GROUP_MEMBER_ADDED);
        m.put(AuditAction.GROUP_MEMBER_REMOVE,  OutboundEventType.GROUP_MEMBER_REMOVED);
        m.put(AuditAction.GROUP_BULK_IMPORT,    OutboundEventType.GROUP_BULK_IMPORTED);

        // Approvals
        m.put(AuditAction.APPROVAL_SUBMITTED,     OutboundEventType.APPROVAL_SUBMITTED);
        m.put(AuditAction.APPROVAL_APPROVED,      OutboundEventType.APPROVAL_APPROVED);
        m.put(AuditAction.APPROVAL_AUTO_APPROVED, OutboundEventType.APPROVAL_AUTO_APPROVED);
        m.put(AuditAction.APPROVAL_REJECTED,      OutboundEventType.APPROVAL_REJECTED);

        // Campaigns + reviews
        m.put(AuditAction.CAMPAIGN_CREATED,   OutboundEventType.CAMPAIGN_CREATED);
        m.put(AuditAction.CAMPAIGN_ACTIVATED, OutboundEventType.CAMPAIGN_ACTIVATED);
        m.put(AuditAction.CAMPAIGN_CLOSED,    OutboundEventType.CAMPAIGN_CLOSED);
        m.put(AuditAction.CAMPAIGN_CANCELLED, OutboundEventType.CAMPAIGN_CANCELLED);
        m.put(AuditAction.CAMPAIGN_EXPIRED,   OutboundEventType.CAMPAIGN_EXPIRED);
        m.put(AuditAction.REVIEW_CONFIRMED,   OutboundEventType.REVIEW_CONFIRMED);
        m.put(AuditAction.REVIEW_REVOKED,     OutboundEventType.REVIEW_REVOKED);
        m.put(AuditAction.REVIEW_AUTO_REVOKED,OutboundEventType.REVIEW_AUTO_REVOKED);

        // SoD
        m.put(AuditAction.SOD_VIOLATION_DETECTED, OutboundEventType.SOD_VIOLATION_DETECTED);
        m.put(AuditAction.SOD_VIOLATION_EXEMPTED, OutboundEventType.SOD_VIOLATION_EXEMPTED);
        m.put(AuditAction.SOD_VIOLATION_RESOLVED, OutboundEventType.SOD_VIOLATION_RESOLVED);

        // Playbooks
        m.put(AuditAction.PLAYBOOK_EXECUTED,    OutboundEventType.PLAYBOOK_EXECUTED);
        m.put(AuditAction.PLAYBOOK_ROLLED_BACK, OutboundEventType.PLAYBOOK_ROLLED_BACK);

        // API tokens
        m.put(AuditAction.API_TOKEN_CREATED, OutboundEventType.API_TOKEN_CREATED);
        m.put(AuditAction.API_TOKEN_REVOKED, OutboundEventType.API_TOKEN_REVOKED);
        m.put(AuditAction.API_TOKEN_ROTATED, OutboundEventType.API_TOKEN_ROTATED);

        // Auditor portal
        m.put(AuditAction.AUDITOR_LINK_CREATED,  OutboundEventType.AUDITOR_LINK_CREATED);
        m.put(AuditAction.AUDITOR_LINK_REVOKED,  OutboundEventType.AUDITOR_LINK_REVOKED);
        m.put(AuditAction.AUDITOR_LINK_ACCESSED, OutboundEventType.AUDITOR_LINK_ACCESSED);

        this.map = Map.copyOf(m);
    }

    public Optional<OutboundEventType> lookup(AuditAction action) {
        return Optional.ofNullable(map.get(action));
    }
}
