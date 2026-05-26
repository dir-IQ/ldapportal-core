// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.enums.OutboundEventType;
import com.ldapportal.entity.enums.AuditAction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionToOutboundTypeMapTest {

    private final AuditActionToOutboundTypeMap map = new AuditActionToOutboundTypeMap();

    @Test
    void forwardableActions_mapToTypes() {
        assertThat(map.lookup(AuditAction.USER_CREATE))
                .contains(OutboundEventType.USER_CREATED);
        assertThat(map.lookup(AuditAction.API_TOKEN_CREATED))
                .contains(OutboundEventType.API_TOKEN_CREATED);
        assertThat(map.lookup(AuditAction.SOD_VIOLATION_DETECTED))
                .contains(OutboundEventType.SOD_VIOLATION_DETECTED);
    }

    @Test
    void excludedActions_returnEmpty() {
        assertThat(map.lookup(AuditAction.LDAP_CHANGE)).isEmpty();
        assertThat(map.lookup(AuditAction.ENTRY_CREATE)).isEmpty();
        assertThat(map.lookup(AuditAction.PASSWORD_RESET)).isEmpty();
        assertThat(map.lookup(AuditAction.INTEGRITY_CHECK)).isEmpty();
        assertThat(map.lookup(AuditAction.LDIF_IMPORT)).isEmpty();
        assertThat(map.lookup(AuditAction.BULK_ATTRIBUTE_UPDATE)).isEmpty();
        assertThat(map.lookup(AuditAction.HR_SYNC_STARTED)).isEmpty();
        assertThat(map.lookup(AuditAction.HR_SYNC_COMPLETED)).isEmpty();
        assertThat(map.lookup(AuditAction.HR_SYNC_FAILED)).isEmpty();
        assertThat(map.lookup(AuditAction.HR_EMPLOYEE_MATCHED)).isEmpty();
        assertThat(map.lookup(AuditAction.HR_ORPHAN_DETECTED)).isEmpty();
        assertThat(map.lookup(AuditAction.APPROVAL_REQUEST_EDITED)).isEmpty();
        assertThat(map.lookup(AuditAction.SOD_POLICY_CREATED)).isEmpty();
        assertThat(map.lookup(AuditAction.SOD_POLICY_UPDATED)).isEmpty();
        assertThat(map.lookup(AuditAction.SOD_POLICY_DELETED)).isEmpty();
        assertThat(map.lookup(AuditAction.SOD_SCAN_EXECUTED)).isEmpty();
        assertThat(map.lookup(AuditAction.SOD_VIOLATION_BLOCKED)).isEmpty();
    }

    /**
     * Every {@link OutboundEventType} must be referenced by at least one
     * {@link AuditAction}; otherwise it's dead wire-contract. If this test
     * fails after adding a new OutboundEventType, add the matching map entry.
     */
    @Test
    void everyOutboundTypeIsProducible() {
        for (OutboundEventType type : OutboundEventType.values()) {
            boolean producible = false;
            for (AuditAction action : AuditAction.values()) {
                if (map.lookup(action).map(t -> t == type).orElse(false)) {
                    producible = true;
                    break;
                }
            }
            assertThat(producible)
                    .as("OutboundEventType %s has no AuditAction that maps to it", type)
                    .isTrue();
        }
    }

    /**
     * Snapshot test: this list is the committed wire contract. Renaming or
     * removing a wireName is a breaking change — consumers depend on these
     * strings. If this test fails because a wireName changed, revert the
     * rename and add a new value instead.
     */
    @Test
    void wireNamesAreStable() {
        assertThat(OutboundEventType.USER_CREATED.wireName()).isEqualTo("user.created");
        assertThat(OutboundEventType.USER_UPDATED.wireName()).isEqualTo("user.updated");
        assertThat(OutboundEventType.GROUP_MEMBER_ADDED.wireName()).isEqualTo("group.member_added");
        assertThat(OutboundEventType.APPROVAL_SUBMITTED.wireName()).isEqualTo("approval.submitted");
        assertThat(OutboundEventType.API_TOKEN_CREATED.wireName()).isEqualTo("api_token.created");
        assertThat(OutboundEventType.SOD_VIOLATION_DETECTED.wireName()).isEqualTo("sod.violation_detected");
        assertThat(OutboundEventType.PLAYBOOK_EXECUTED.wireName()).isEqualTo("playbook.executed");
        assertThat(OutboundEventType.CAMPAIGN_CLOSED.wireName()).isEqualTo("campaign.closed");
        assertThat(OutboundEventType.AUDITOR_LINK_ACCESSED.wireName()).isEqualTo("auditor_link.accessed");
    }
}
