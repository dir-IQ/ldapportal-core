// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.enums;

/**
 * The wire vocabulary of the event backbone. Each value corresponds to one
 * (or more) {@code AuditAction} values via the translation table in
 * {@code AuditActionToOutboundTypeMap}. The {@code wireName} is what appears
 * on the wire as {@code envelope.type} and {@code X-LDAPPortal-Event-Type};
 * it is stable forever — values may be added, never renamed or removed.
 *
 * <p>{@code resourceType} populates the envelope's {@code resource.type}
 * field.</p>
 */
public enum OutboundEventType {

    // User lifecycle
    USER_CREATED      ("user.created",       "user"),
    USER_UPDATED      ("user.updated",       "user"),
    USER_DELETED      ("user.deleted",       "user"),
    USER_ENABLED      ("user.enabled",       "user"),
    USER_DISABLED     ("user.disabled",      "user"),
    USER_MOVED        ("user.moved",         "user"),

    // Group lifecycle
    GROUP_CREATED         ("group.created",         "group"),
    GROUP_UPDATED         ("group.updated",         "group"),
    GROUP_DELETED         ("group.deleted",         "group"),
    GROUP_MEMBER_ADDED    ("group.member_added",    "group"),
    GROUP_MEMBER_REMOVED  ("group.member_removed",  "group"),
    GROUP_BULK_IMPORTED   ("group.bulk_imported",   "group"),

    // Approvals
    APPROVAL_SUBMITTED     ("approval.submitted",     "approval"),
    APPROVAL_APPROVED      ("approval.approved",      "approval"),
    APPROVAL_AUTO_APPROVED ("approval.auto_approved", "approval"),
    APPROVAL_REJECTED      ("approval.rejected",      "approval"),

    // Access review campaigns
    CAMPAIGN_CREATED   ("campaign.created",    "campaign"),
    CAMPAIGN_ACTIVATED ("campaign.activated",  "campaign"),
    CAMPAIGN_CLOSED    ("campaign.closed",     "campaign"),
    CAMPAIGN_CANCELLED ("campaign.cancelled",  "campaign"),
    CAMPAIGN_EXPIRED   ("campaign.expired",    "campaign"),
    REVIEW_CONFIRMED   ("review.confirmed",    "review"),
    REVIEW_REVOKED     ("review.revoked",      "review"),
    REVIEW_AUTO_REVOKED("review.auto_revoked", "review"),

    // SoD
    SOD_VIOLATION_DETECTED ("sod.violation_detected", "sod_policy"),
    SOD_VIOLATION_EXEMPTED ("sod.violation_exempted", "sod_policy"),
    SOD_VIOLATION_RESOLVED ("sod.violation_resolved", "sod_policy"),

    // Playbooks
    PLAYBOOK_EXECUTED    ("playbook.executed",     "playbook"),
    PLAYBOOK_ROLLED_BACK ("playbook.rolled_back",  "playbook"),

    // API tokens
    API_TOKEN_CREATED ("api_token.created", "api_token"),
    API_TOKEN_REVOKED ("api_token.revoked", "api_token"),
    API_TOKEN_ROTATED ("api_token.rotated", "api_token"),

    // Auditor portal links
    AUDITOR_LINK_CREATED  ("auditor_link.created",  "auditor_link"),
    AUDITOR_LINK_REVOKED  ("auditor_link.revoked",  "auditor_link"),
    AUDITOR_LINK_ACCESSED ("auditor_link.accessed", "auditor_link");

    private final String wireName;
    private final String resourceType;

    OutboundEventType(String wireName, String resourceType) {
        this.wireName = wireName;
        this.resourceType = resourceType;
    }

    public String wireName()    { return wireName; }
    public String resourceType(){ return resourceType; }
}
