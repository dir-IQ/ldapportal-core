// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
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
 *   DELETE /api/directories/{directoryId}/groups/members   — remove member (?dn=)
 * </pre>
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
            @Valid @RequestBody UpdateEntryRequest req) {
        return service.updateGroup(directoryId, principal, dn, req);
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

    @DeleteMapping("/members")
    @RequiresFeature(FeatureKey.GROUP_MANAGE_MEMBERS)
    public ResponseEntity<Void> removeMember(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody MemberRequest req) {
        service.removeGroupMember(directoryId, principal, dn, req.memberAttribute(), req.memberValue());
        return ResponseEntity.noContent().build();
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
}
