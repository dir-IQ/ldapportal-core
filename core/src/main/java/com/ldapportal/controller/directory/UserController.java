// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.ldap.BulkAttributeUpdateRequest;
import com.ldapportal.dto.ldap.BulkAttributeUpdateResult;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MoveUserRequest;
import com.ldapportal.dto.ldap.ResetPasswordLdapRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.ProvisioningProfile;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LDAP user operations for a specific directory.
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/users")
@RequiredArgsConstructor
public class UserController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT      = 2000;

    private final LdapOperationService service;
    private final ApprovalWorkflowService approvalService;
    private final com.ldapportal.service.ProvisioningProfileService profileService;
    private final com.ldapportal.service.PasswordPolicyService passwordPolicyService;
    private final com.ldapportal.service.ApprovalNotificationService notificationService;
    private final com.ldapportal.auth.PermissionService permissionService;

    @GetMapping
    @RequiresFeature(FeatureKey.USER_READ)
    public List<LdapEntryResponse> search(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String baseDn,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false, defaultValue = "") String attributes) {

        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String[] attrArray = attributes.isBlank() ? new String[0] : attributes.split(",");
        return service.searchUsers(directoryId, principal, filter, baseDn, safeLimit, attrArray);
    }

    @PostMapping
    @RequiresFeature(FeatureKey.USER_CREATE)
    public ResponseEntity<?> create(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateEntryRequest req) {

        // Check if approval is required for the matching profile
        Optional<ProvisioningProfile> profile = approvalService.findProfileForDn(directoryId, req.dn());

        // Enforce profile-level scope before applying defaults — an admin must
        // have a role in the matched profile, not just any profile in the directory.
        if (profile.isPresent()) {
            permissionService.requireProfileAccess(principal, profile.get().getId());
            Map<String, List<String>> attrs = new LinkedHashMap<>(req.attributes());
            profileService.applyDefaults(profile.get().getId(), attrs);
            req = new CreateEntryRequest(req.dn(), attrs);
        }

        // Submit for approval — handles both profiled and unprovisioned OUs
        Optional<PendingApproval> pendingApproval = approvalService.checkAndSubmitForApproval(
                directoryId, req.dn(), principal, ApprovalRequestType.USER_CREATE, req);
        if (pendingApproval.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "message", "User creation submitted for approval",
                            "approvalId", pendingApproval.get().getId()));
        }

        LdapEntryResponse result = service.createUser(directoryId, principal, req,
                profile.map(ProvisioningProfile::getId).orElse(null));

        // Apply the profile's effective group assignments (own groups +
        // additional + auto-include) to the new user. Previously this
        // was handled client-side by UserListView, which left the bulk
        // import, approval-approved, and direct-API paths missing the
        // group memberships the profile declared. Doing it server-side
        // here (and in ApprovalWorkflowService.executeUserCreate for
        // the approval path) makes the behaviour consistent across
        // all entry points.
        if (profile.isPresent()) {
            profileService.applyGroupAssignmentsToUser(
                    directoryId, profile.get().getId(), req.dn(), principal);
        }

        // Apply the profile's effective group assignments (own groups +
        // additional + auto-include) to the new user. Previously this
        // was handled client-side by UserListView, which left the bulk
        // import, approval-approved, and direct-API paths missing the
        // group memberships the profile declared. Doing it server-side
        // here (and in ApprovalWorkflowService.executeUserCreate for
        // the approval path) makes the behaviour consistent across
        // all entry points.
        if (profile.isPresent()) {
            profileService.applyGroupAssignmentsToUser(
                    directoryId, profile.get().getId(), req.dn(), principal);
        }

        // Email password to user if profile is configured for it
        if (profile.isPresent() && profile.get().isEmailPasswordToUser()) {
            Map<String, List<String>> attrs = req.attributes();
            List<String> mailValues = attrs.get("mail");
            List<String> pwdValues  = attrs.get("userPassword");
            if (mailValues != null && !mailValues.isEmpty()
                    && pwdValues != null && !pwdValues.isEmpty()) {
                String displayName = attrs.containsKey("cn") && !attrs.get("cn").isEmpty()
                        ? attrs.get("cn").get(0) : "User";
                notificationService.sendPasswordEmail(
                        mailValues.get(0), displayName, pwdValues.get(0));
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/entry")
    @RequiresFeature(FeatureKey.USER_READ)
    public LdapEntryResponse get(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @RequestParam(required = false, defaultValue = "") String attributes) {

        String[] attrArray = attributes.isBlank() ? new String[0] : attributes.split(",");
        return service.getUser(directoryId, principal, dn, attrArray);
    }

    @PutMapping("/entry")
    @RequiresFeature(FeatureKey.USER_EDIT)
    public LdapEntryResponse update(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody UpdateEntryRequest req) {
        return service.updateUser(directoryId, principal, dn, req);
    }

    // NOTE (M4): The following destructive operations (delete, enable/disable, password
    // reset, bulk attribute update) intentionally have NO approval workflow. This is a
    // deliberate product decision: approval gates cover creation and movement workflows
    // where new accounts/access are provisioned. Destructive operations are gated only
    // by feature permissions (USER_DELETE, USER_ENABLE_DISABLE, USER_RESET_PASSWORD,
    // BULK_ATTRIBUTE_UPDATE). If approval is needed for these in the future, wire them
    // through ApprovalWorkflowService.checkAndSubmitForApproval() with new request types.
    @DeleteMapping("/entry")
    @RequiresFeature(FeatureKey.USER_DELETE)
    public ResponseEntity<Void> delete(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        service.deleteUser(directoryId, principal, dn);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/enable")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public ResponseEntity<Void> enable(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        service.enableUser(directoryId, principal, dn);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/disable")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public ResponseEntity<Void> disable(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        service.disableUser(directoryId, principal, dn);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @RequiresFeature(FeatureKey.USER_RESET_PASSWORD)
    public ResponseEntity<Void> resetPassword(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody ResetPasswordLdapRequest req) {
        service.resetPassword(directoryId, principal, dn, req.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bulk-update")
    @RequiresFeature(FeatureKey.BULK_ATTRIBUTE_UPDATE)
    public BulkAttributeUpdateResult bulkUpdate(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BulkAttributeUpdateRequest req) {
        return service.bulkUpdateAttributes(directoryId, principal, req);
    }

    @PostMapping("/move")
    @RequiresFeature(FeatureKey.USER_MOVE)
    public ResponseEntity<?> move(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody MoveUserRequest req) {

        Optional<PendingApproval> pendingApproval = approvalService.checkAndSubmitForApproval(
                directoryId, dn, principal, ApprovalRequestType.USER_MOVE,
                Map.of("dn", dn, "request", req));
        if (pendingApproval.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "message", "User move submitted for approval",
                            "approvalId", pendingApproval.get().getId()));
        }

        service.moveUser(directoryId, principal, dn, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/password-status")
    @RequiresFeature(FeatureKey.USER_READ)
    public Map<String, Object> passwordStatus(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        return passwordPolicyService.getPasswordStatus(directoryId, principal, dn);
    }
}
