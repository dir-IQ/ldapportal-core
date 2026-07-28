// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import com.ldapportal.core.entitlement.EditionScoped;
import com.ldapportal.core.entitlement.Entitlement;

import java.util.Arrays;

/**
 * All recordable audit actions.
 * DB values use dot-notation; a custom JPA converter handles the mapping.
 *
 * <p>Implements {@link EditionScoped}: actions emitted only by non-community
 * features (access reviews, segregation of duties, HR sync, the auditor portal)
 * carry the entitlement that gates them, so the audit-action filter catalogue is
 * filtered through {@link com.ldapportal.core.entitlement.EntitlementService#exposed(Class)}
 * rather than a hand-maintained exclude list on the client.</p>
 */
public enum AuditAction implements EditionScoped {

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
    SCHEMA_UPDATE        ("schema.update"),

    // ── Approval workflow ──────────────────────────────────────────────────────
    APPROVAL_SUBMITTED   ("approval.submitted"),
    APPROVAL_APPROVED    ("approval.approved"),
    APPROVAL_AUTO_APPROVED("approval.auto_approved"),
    APPROVAL_REJECTED    ("approval.rejected"),
    APPROVAL_REQUEST_EDITED("approval.request_edited"),

    // ── Access review campaigns (GOVERNANCE) ────────────────────────────────
    CAMPAIGN_CREATED     ("campaign.created",        Entitlement.GOVERNANCE),
    CAMPAIGN_ACTIVATED   ("campaign.activated",      Entitlement.GOVERNANCE),
    CAMPAIGN_CLOSED      ("campaign.closed",         Entitlement.GOVERNANCE),
    CAMPAIGN_CANCELLED   ("campaign.cancelled",      Entitlement.GOVERNANCE),
    CAMPAIGN_EXPIRED     ("campaign.expired",        Entitlement.GOVERNANCE),
    REVIEW_CONFIRMED     ("review.confirmed",        Entitlement.GOVERNANCE),
    REVIEW_REVOKED       ("review.revoked",          Entitlement.GOVERNANCE),
    REVIEW_AUTO_REVOKED  ("review.auto_revoked",     Entitlement.GOVERNANCE),

    // ── SoD policy engine (GOVERNANCE) ──────────────────────────────────────
    SOD_POLICY_CREATED   ("sod.policy_created",      Entitlement.GOVERNANCE),
    SOD_POLICY_UPDATED   ("sod.policy_updated",      Entitlement.GOVERNANCE),
    SOD_POLICY_DELETED   ("sod.policy_deleted",      Entitlement.GOVERNANCE),
    SOD_SCAN_EXECUTED    ("sod.scan_executed",       Entitlement.GOVERNANCE),
    SOD_VIOLATION_DETECTED("sod.violation_detected", Entitlement.GOVERNANCE),
    SOD_VIOLATION_EXEMPTED("sod.violation_exempted", Entitlement.GOVERNANCE),
    SOD_VIOLATION_BLOCKED ("sod.violation_blocked",  Entitlement.GOVERNANCE),
    SOD_VIOLATION_RESOLVED("sod.violation_resolved", Entitlement.GOVERNANCE),

    // ── Lifecycle playbooks ─────────────────────────────────────────────────
    PLAYBOOK_EXECUTED    ("playbook.executed"),
    PLAYBOOK_ROLLED_BACK ("playbook.rolled_back"),

    // ── Provisioning profile config (CRUD on the profile itself) ───────────
    PROFILE_CREATE       ("profile.create"),
    PROFILE_UPDATE       ("profile.update"),
    PROFILE_DELETE       ("profile.delete"),
    PROFILE_CLONE        ("profile.clone"),

    // ── Application account CRUD (admins + superadmins) ────────────────────
    ACCOUNT_CREATE              ("account.create"),
    ACCOUNT_UPDATE              ("account.update"),
    ACCOUNT_DELETE              ("account.delete"),
    ACCOUNT_PERMISSION_CHANGED  ("account.permission_changed"),

    // ── HR integration (HR_SYNC) ────────────────────────────────────────────
    HR_SYNC_STARTED      ("hr.sync_started",     Entitlement.HR_SYNC),
    HR_SYNC_COMPLETED    ("hr.sync_completed",   Entitlement.HR_SYNC),
    HR_SYNC_FAILED       ("hr.sync_failed",      Entitlement.HR_SYNC),
    HR_EMPLOYEE_MATCHED  ("hr.employee_matched", Entitlement.HR_SYNC),
    HR_ORPHAN_DETECTED   ("hr.orphan_detected",  Entitlement.HR_SYNC),

    // ── Auditor portal (GOVERNANCE) ─────────────────────────────────────────
    AUDITOR_LINK_CREATED ("auditor.link_created",  Entitlement.GOVERNANCE),
    AUDITOR_LINK_REVOKED ("auditor.link_revoked",  Entitlement.GOVERNANCE),
    AUDITOR_LINK_ACCESSED("auditor.link_accessed", Entitlement.GOVERNANCE),

    // ── Changelog-sourced (raw LDAP changelog entry) ──────────────────────────
    LDAP_CHANGE          ("ldap.change"),

    // ── API tokens (machine auth) ─────────────────────────────────────────────
    API_TOKEN_CREATED    ("api_token.created"),
    API_TOKEN_UPDATED    ("api_token.updated"),
    API_TOKEN_REVOKED    ("api_token.revoked"),
    API_TOKEN_ROTATED    ("api_token.rotated");
    // Directory-sync audit actions are reintroduced (as sync.*) when the
    // membership-engine lands; the legacy replication.* / reconciliation.*
    // actions were removed with that subsystem.

    private final String dbValue;

    /** Entitlement gating exposure of this action in the filter catalogue, or
     *  null when it's part of the core, edition-agnostic surface. */
    private final Entitlement requiredEntitlement;

    AuditAction(String dbValue) {
        this(dbValue, null);
    }

    AuditAction(String dbValue, Entitlement requiredEntitlement) {
        this.dbValue = dbValue;
        this.requiredEntitlement = requiredEntitlement;
    }

    public String getDbValue() {
        return dbValue;
    }

    @Override
    public Entitlement requiredEntitlement() {
        return requiredEntitlement;
    }

    public static AuditAction fromDbValue(String value) {
        return Arrays.stream(values())
                .filter(a -> a.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown audit action: " + value));
    }
}
