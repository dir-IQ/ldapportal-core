// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import java.util.Arrays;

/**
 * All recordable audit actions.
 * DB values use dot-notation; a custom JPA converter handles the mapping.
 */
public enum AuditAction {

    // ── User operations ───────────────────────────────────────────────────────
    USER_CREATE          ("user.create"),
    USER_UPDATE          ("user.update"),
    USER_DELETE          ("user.delete"),
    USER_ENABLE          ("user.enable"),
    USER_DISABLE         ("user.disable"),
    USER_MOVE            ("user.move"),
    PASSWORD_RESET       ("password.reset"),

    // ── Group operations ──────────────────────────────────────────────────────
    GROUP_CREATE         ("group.create"),
    GROUP_UPDATE         ("group.update"),
    GROUP_DELETE         ("group.delete"),
    GROUP_MEMBER_ADD     ("group.member_add"),
    GROUP_MEMBER_REMOVE  ("group.member_remove"),
    GROUP_BULK_IMPORT    ("group.bulk_import"),

    // ── Generic entry operations (superadmin browser) ─────────────────────────
    ENTRY_CREATE         ("entry.create"),
    ENTRY_UPDATE         ("entry.update"),
    ENTRY_DELETE         ("entry.delete"),
    ENTRY_MOVE           ("entry.move"),
    ENTRY_RENAME         ("entry.rename"),
    LDIF_IMPORT          ("ldif.import"),
    INTEGRITY_CHECK      ("integrity.check"),
    BULK_ATTRIBUTE_UPDATE("bulk.attribute_update"),

    // ── Approval workflow ──────────────────────────────────────────────────────
    APPROVAL_SUBMITTED   ("approval.submitted"),
    APPROVAL_APPROVED    ("approval.approved"),
    APPROVAL_AUTO_APPROVED("approval.auto_approved"),
    APPROVAL_REJECTED    ("approval.rejected"),
    APPROVAL_REQUEST_EDITED("approval.request_edited"),

    // ── Access review campaigns ─────────────────────────────────────────────
    CAMPAIGN_CREATED     ("campaign.created"),
    CAMPAIGN_ACTIVATED   ("campaign.activated"),
    CAMPAIGN_CLOSED      ("campaign.closed"),
    CAMPAIGN_CANCELLED   ("campaign.cancelled"),
    CAMPAIGN_EXPIRED     ("campaign.expired"),
    REVIEW_CONFIRMED     ("review.confirmed"),
    REVIEW_REVOKED       ("review.revoked"),
    REVIEW_AUTO_REVOKED  ("review.auto_revoked"),

    // ── SoD policy engine ──────────────────────────────────────────────────
    SOD_POLICY_CREATED   ("sod.policy_created"),
    SOD_POLICY_UPDATED   ("sod.policy_updated"),
    SOD_POLICY_DELETED   ("sod.policy_deleted"),
    SOD_SCAN_EXECUTED    ("sod.scan_executed"),
    SOD_VIOLATION_DETECTED("sod.violation_detected"),
    SOD_VIOLATION_EXEMPTED("sod.violation_exempted"),
    SOD_VIOLATION_BLOCKED ("sod.violation_blocked"),
    SOD_VIOLATION_RESOLVED("sod.violation_resolved"),

    // ── Lifecycle playbooks ─────────────────────────────────────────────────
    PLAYBOOK_EXECUTED    ("playbook.executed"),
    PLAYBOOK_ROLLED_BACK ("playbook.rolled_back"),

    // ── HR integration ──────────────────────────────────────────────────────
    HR_SYNC_STARTED      ("hr.sync_started"),
    HR_SYNC_COMPLETED    ("hr.sync_completed"),
    HR_SYNC_FAILED       ("hr.sync_failed"),
    HR_EMPLOYEE_MATCHED  ("hr.employee_matched"),
    HR_ORPHAN_DETECTED   ("hr.orphan_detected"),

    // ── Auditor portal ──────────────────────────────────────────────────────
    AUDITOR_LINK_CREATED ("auditor.link_created"),
    AUDITOR_LINK_REVOKED ("auditor.link_revoked"),
    AUDITOR_LINK_ACCESSED("auditor.link_accessed"),

    // ── Changelog-sourced (raw LDAP changelog entry) ──────────────────────────
    LDAP_CHANGE          ("ldap.change"),

    // ── API tokens (machine auth) ─────────────────────────────────────────────
    API_TOKEN_CREATED    ("api_token.created"),
    API_TOKEN_REVOKED    ("api_token.revoked"),
    API_TOKEN_ROTATED    ("api_token.rotated");

    private final String dbValue;

    AuditAction(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static AuditAction fromDbValue(String value) {
        return Arrays.stream(values())
                .filter(a -> a.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown audit action: " + value));
    }
}
