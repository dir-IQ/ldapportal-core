// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;

import com.ldapportal.dto.admin.FeaturePermissionRequest;
import com.ldapportal.dto.admin.ProfileRoleRequest;
import com.ldapportal.dto.admin.ProfileRoleResponse;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.service.AdminManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin account management and permission assignment.
 *
 * <pre>
 *   GET    /api/v1/superadmin/admins                                       — list
 *   POST   /api/v1/superadmin/admins                                       — create
 *   GET    /api/v1/superadmin/admins/{id}                                  — get
 *   PUT    /api/v1/superadmin/admins/{id}                                  — update
 *   PUT    /api/v1/superadmin/admins/by-username/{username}               — idempotent upsert (IaC)
 *   DELETE /api/v1/superadmin/admins/{id}                                  — delete
 *   GET    /api/v1/superadmin/admins/{id}/permissions                      — all dims
 *   PUT    /api/v1/superadmin/admins/{id}/permissions/profile-roles        — dim 1+2
 *   DELETE /api/v1/superadmin/admins/{id}/permissions/profile-roles/{profileId}
 *   PUT    /api/v1/superadmin/admins/{id}/permissions/features             — dim 3
 *   DELETE /api/v1/superadmin/admins/{id}/permissions/features/{key}       — clear override
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/superadmin/admins")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.MANAGE_APPLICATION_ACCOUNTS)
@RequiredArgsConstructor
public class AdminManagementController {

    private final AdminManagementService service;
    private final com.ldapportal.service.EffectivePermissionsService effectivePermissionsService;

    // ── Account CRUD ──────────────────────────────────────────────────────────

    @GetMapping
    public List<AdminAccountResponse> list() {
        return service.listAdmins();
    }

    @PostMapping
    public ResponseEntity<AdminAccountResponse> create(@Valid @RequestBody AdminAccountRequest req,
                                                        @org.springframework.security.core.annotation.AuthenticationPrincipal
                                                                com.ldapportal.auth.AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAdmin(req, principal));
    }

    /**
     * Combined create — account plus initial profile roles and feature
     * permissions in one transactional call. The plain {@link #create} is
     * preserved for callers that don't want to provision permissions in
     * the same step.
     */
    @PostMapping("/with-permissions")
    public ResponseEntity<AdminAccountResponse> createWithPermissions(
            @Valid @RequestBody com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAdminWithPermissions(req, principal));
    }

    @GetMapping("/{adminId}")
    public ResponseEntity<AdminAccountResponse> get(@PathVariable UUID adminId) {
        return withETag(service.getAdmin(adminId), HttpStatus.OK);
    }

    @PutMapping("/{adminId}")
    public ResponseEntity<AdminAccountResponse> update(
            @PathVariable UUID adminId,
            @Valid @RequestBody AdminAccountRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = org.springframework.http.HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        AdminAccountResponse resp = service.updateAdmin(
                adminId, req, principal, com.ldapportal.web.ETagSupport.parseIfMatch(ifMatch));
        return withETag(resp, HttpStatus.OK);
    }

    /**
     * Idempotent create-or-update of an admin and its full permission set,
     * keyed by the stable username. Re-applying the same declaration converges
     * to identical state — profile roles and feature overrides are replaced to
     * match the body. Returns 201 on the first apply (created), 200 thereafter
     * (updated in place). Scoped to ADMIN-role accounts.
     *
     * <p>An optional {@code If-Match} header makes the update-path apply
     * conditional on the account still being at the version the caller last saw
     * (412 on mismatch); it is ignored when the apply creates the account.</p>
     */
    @PutMapping("/by-username/{username}")
    public ResponseEntity<AdminAccountResponse> upsertByUsername(
            @PathVariable String username,
            @Valid @RequestBody com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = org.springframework.http.HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        AdminManagementService.AdminUpsertOutcome outcome = service.upsertByUsername(
                username, req, principal, com.ldapportal.web.ETagSupport.parseIfMatch(ifMatch));
        return withETag(outcome.response(), outcome.created() ? HttpStatus.CREATED : HttpStatus.OK);
    }

    /** Attach the account's version as a strong ETag for optimistic concurrency. */
    private static ResponseEntity<AdminAccountResponse> withETag(
            AdminAccountResponse resp, HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(com.ldapportal.web.ETagSupport.format(resp.version()))
                .body(resp);
    }

    @DeleteMapping("/{adminId}")
    public ResponseEntity<Void> delete(@PathVariable UUID adminId,
                                        @org.springframework.security.core.annotation.AuthenticationPrincipal
                                                com.ldapportal.auth.AuthPrincipal principal) {
        service.deleteAdmin(adminId, principal);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reset an admin's LOCAL password. Mirrors the
     * {@code /superadmin/superadmins/{id}/reset-password} endpoint so the
     * UI has parity between the two account roles. New password must
     * satisfy {@link com.ldapportal.service.AccountPasswordPolicy}.
     */
    @PostMapping("/{adminId}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID adminId,
            @Valid @RequestBody com.ldapportal.dto.superadmin.ResetPasswordRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        service.resetAdminPassword(adminId, req.newPassword(), principal);
        return ResponseEntity.noContent().build();
    }

    // ── Permission summary ────────────────────────────────────────────────────

    @GetMapping("/{adminId}/permissions")
    public AdminPermissionsResponse getPermissions(@PathVariable UUID adminId) {
        return service.getPermissions(adminId);
    }

    /**
     * Effective-permissions breakdown: per profile, which features are allowed
     * and which dimension of the model (base role / admin-wide override /
     * per-profile override) produced the outcome. The superadmin UI renders
     * this as a "why can this admin do X?" explainer without requiring anyone
     * to cross-reference three tables by hand.
     */
    @GetMapping("/{adminId}/effective-permissions")
    public com.ldapportal.dto.admin.EffectivePermissionsResponse getEffectivePermissions(@PathVariable UUID adminId) {
        return effectivePermissionsService.resolve(adminId);
    }

    // ── Dimension 1+2: profile roles ──────────────────────────────────────────

    @PutMapping("/{adminId}/permissions/profile-roles")
    public ProfileRoleResponse assignProfileRole(
            @PathVariable UUID adminId,
            @Valid @RequestBody ProfileRoleRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        return service.assignProfileRole(adminId, req, principal);
    }

    @DeleteMapping("/{adminId}/permissions/profile-roles/{profileId}")
    public ResponseEntity<Void> removeProfileRole(@PathVariable UUID adminId,
                                                   @PathVariable UUID profileId,
                                                   @org.springframework.security.core.annotation.AuthenticationPrincipal
                                                           com.ldapportal.auth.AuthPrincipal principal) {
        service.removeProfileRole(adminId, profileId, principal);
        return ResponseEntity.noContent().build();
    }

    // ── Dimension 3: feature permissions ─────────────────────────────────────

    @PutMapping("/{adminId}/permissions/features")
    public ResponseEntity<Void> setFeaturePermissions(
            @PathVariable UUID adminId,
            @RequestBody List<@Valid FeaturePermissionRequest> permissions,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        service.setFeaturePermissions(adminId, permissions, principal);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{adminId}/permissions/features/{featureKey}")
    public ResponseEntity<Void> clearFeaturePermission(
            @PathVariable UUID adminId,
            @PathVariable FeatureKey featureKey,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        service.clearFeaturePermission(adminId, featureKey, principal);
        return ResponseEntity.noContent().build();
    }
}
