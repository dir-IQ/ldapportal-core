// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.ldap.BulkMemberRemoveResult;
import com.ldapportal.dto.ldap.BulkMemberRequest;
import com.ldapportal.dto.ldap.BulkMemberResult;
import com.ldapportal.dto.ldap.BulkMemberResult.BulkMemberError;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MemberRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LDAP group operations for a specific directory.
 *
 * <pre>
 *   GET    /api/directories/{directoryId}/groups           — search
 *   POST   /api/directories/{directoryId}/groups           — create
 *   GET    /api/directories/{directoryId}/groups/entry     — get by DN (?dn=)
 *   DELETE /api/directories/{directoryId}/groups/entry     — delete by DN (?dn=)
 *   GET    /api/directories/{directoryId}/groups/members   — list members (?dn= &memberAttr=)
 *   POST   /api/directories/{directoryId}/groups/members   — add member (?dn=)
 *   DELETE /api/directories/{directoryId}/groups/members   — remove member (?dn= &memberAttribute= &memberValue=)
 *   POST   /api/directories/{directoryId}/groups/members/bulk/remove — remove several members (?dn=)
 * </pre>
 *
 * <p>Member removal carries the member in query parameters (one) or a POST
 * body (several), not in a DELETE body: reverse proxies such as WebSEAL
 * don't reliably forward a DELETE request's body, and a dropped body reached
 * the portal as a 400. The DELETE forms still accept a body for existing
 * API callers.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/groups")
@RequiredArgsConstructor
public class GroupController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT      = 2000;

    private final LdapOperationService service;
    private final ApprovalWorkflowService approvalService;

    // ── Search ────────────────────────────────────────────────────────────────

    @GetMapping
    @RequiresFeature(FeatureKey.GROUP_READ)
    public List<LdapEntryResponse> search(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String baseDn,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false, defaultValue = "") String attributes) {

        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String[] attrArray = attributes.isBlank() ? new String[0] : attributes.split(",");
        return service.searchGroups(directoryId, principal, filter, baseDn, safeLimit, attrArray);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @RequiresFeature(FeatureKey.GROUP_CREATE_DELETE)
    public ResponseEntity<LdapEntryResponse> create(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateEntryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createGroup(directoryId, principal, req));
    }

    // ── Get / Delete by DN ────────────────────────────────────────────────────

    @GetMapping("/entry")
    @RequiresFeature(FeatureKey.GROUP_READ)
    public LdapEntryResponse get(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @RequestParam(required = false, defaultValue = "") String attributes) {

        String[] attrArray = attributes.isBlank() ? new String[0] : attributes.split(",");
        return service.getGroup(directoryId, principal, dn, attrArray);
    }

    @PutMapping("/entry")
    @RequiresFeature(FeatureKey.GROUP_EDIT)
    public LdapEntryResponse update(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            // Optional optimistic-concurrency precondition: the modifyTimestamp
            // the client loaded. Mismatch → 412; absent → unconditional update.
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "If-Unmodified-Since-LDAP", required = false) String ifUnmodifiedSince,
            @Valid @RequestBody UpdateEntryRequest req) {
        return service.updateGroup(directoryId, principal, dn, req, ifUnmodifiedSince);
    }

    // NOTE (M4): Group delete and member remove intentionally have NO approval workflow.
    // See UserController for the full rationale. Gated by feature permissions only.
    @DeleteMapping("/entry")
    @RequiresFeature(FeatureKey.GROUP_CREATE_DELETE)
    public ResponseEntity<Void> delete(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        service.deleteGroup(directoryId, principal, dn);
        return ResponseEntity.noContent().build();
    }

    // ── Member management ─────────────────────────────────────────────────────

    /**
     * Lists all values of the given membership attribute for the group.
     *
     * @param dn              group distinguished name
     * @param memberAttribute attribute to read (e.g. {@code member}, {@code memberUid})
     */
    @GetMapping("/members")
    @RequiresFeature(FeatureKey.GROUP_READ)
    public List<String> getMembers(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @RequestParam(defaultValue = "member") String memberAttribute) {
        return service.getGroupMembers(directoryId, principal, dn, memberAttribute);
    }

    @PostMapping("/members")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<?> addMember(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody MemberRequest req) {

        // Check approval — handles both profiled and unprovisioned OUs
        Optional<PendingApproval> pendingApproval = approvalService.checkAndSubmitForApproval(
                directoryId, dn, principal, ApprovalRequestType.GROUP_MEMBER_ADD,
                Map.of("groupDn", dn,
                        "memberAttribute", req.memberAttribute(),
                        "memberValue", req.memberValue()));
        if (pendingApproval.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "message", "Group member addition submitted for approval",
                            "approvalId", pendingApproval.get().getId()));
        }

        service.addGroupMember(directoryId, principal, dn, req.memberAttribute(), req.memberValue());
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes one member. The member is given as {@code memberAttribute} and
     * {@code memberValue} query parameters; a JSON body
     * ({@link MemberRequest}) is still accepted when they're absent.
     */
    @DeleteMapping("/members")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<Void> removeMember(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @RequestParam(required = false) String memberAttribute,
            @RequestParam(required = false) String memberValue,
            @Valid @RequestBody(required = false) MemberRequest req) {
        String attr = memberAttribute;
        String value = memberValue;
        if (isBlank(attr) && isBlank(value) && req != null) {
            attr = req.memberAttribute();
            value = req.memberValue();
        }
        if (isBlank(attr) || isBlank(value)) {
            throw new IllegalArgumentException("memberAttribute and memberValue are required");
        }
        service.removeGroupMember(directoryId, principal, dn, attr, value);
        return ResponseEntity.noContent().build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PostMapping("/members/bulk")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<?> addMembersBulk(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody BulkMemberRequest req) {

        // Check approval for the group DN — same gate as single-member endpoint
        Optional<PendingApproval> pendingApproval = approvalService.checkAndSubmitForApproval(
                directoryId, dn, principal, ApprovalRequestType.GROUP_MEMBER_ADD,
                Map.of("groupDn", dn,
                        "memberAttribute", req.memberAttribute(),
                        "memberValues", req.memberValues()));
        if (pendingApproval.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "message", "Bulk group member addition submitted for approval",
                            "approvalId", pendingApproval.get().getId()));
        }

        int added = 0;
        List<BulkMemberError> errors = new ArrayList<>();

        for (String memberValue : req.memberValues()) {
            try {
                service.addGroupMember(directoryId, principal, dn, req.memberAttribute(), memberValue);
                added++;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                errors.add(new BulkMemberError(memberValue, msg));
            }
        }

        return ResponseEntity.ok(new BulkMemberResult(added, errors.size(), errors));
    }

    /**
     * Removes several members from a group in one request. Like single-member
     * removal (see the M4 note above) this has no approval gate; each value is
     * attempted independently and failures are reported per value.
     */
    @PostMapping("/members/bulk/remove")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<BulkMemberRemoveResult> removeMembersBulk(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody BulkMemberRequest req) {
        return ResponseEntity.ok(removeMembers(directoryId, principal, dn, req));
    }

    /**
     * The former form of {@link #removeMembersBulk}, kept for existing API
     * callers: a DELETE with a body, which a reverse proxy may drop.
     */
    @DeleteMapping("/members/bulk")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<BulkMemberRemoveResult> removeMembersBulkViaDelete(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody BulkMemberRequest req) {
        return ResponseEntity.ok(removeMembers(directoryId, principal, dn, req));
    }

    private BulkMemberRemoveResult removeMembers(UUID directoryId, AuthPrincipal principal, String dn,
                                                 BulkMemberRequest req) {
        int removed = 0;
        List<BulkMemberError> errors = new ArrayList<>();

        for (String memberValue : req.memberValues()) {
            try {
                service.removeGroupMember(directoryId, principal, dn, req.memberAttribute(), memberValue);
                removed++;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                errors.add(new BulkMemberError(memberValue, msg));
            }
        }

        return new BulkMemberRemoveResult(removed, errors.size(), errors);
    }
}
