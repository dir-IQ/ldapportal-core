// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;

import com.ldapportal.dto.admin.FeaturePermissionRequest;
import com.ldapportal.dto.admin.ProfileRoleRequest;
import com.ldapportal.dto.admin.ProfileRoleResponse;
import com.ldapportal.entity.enums.FeatureKey;
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
    public ResponseEntity<AdminAccountResponse> create(@Valid @RequestBody AdminAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAdmin(req));
    }

    /**
     * Combined create — account plus initial profile roles and feature
     * permissions in one transactional call. The plain {@link #create} is
     * preserved for callers that don't want to provision permissions in
     * the same step.
     */
    @PostMapping("/with-permissions")
    public ResponseEntity<AdminAccountResponse> createWithPermissions(
            @Valid @RequestBody com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createAdminWithPermissions(req));
    }

    @GetMapping("/{adminId}")
    public AdminAccountResponse get(@PathVariable UUID adminId) {
        return service.getAdmin(adminId);
    }

    @PutMapping("/{adminId}")
    public AdminAccountResponse update(@PathVariable UUID adminId,
                                       @Valid @RequestBody AdminAccountRequest req) {
        return service.updateAdmin(adminId, req);
    }

    @DeleteMapping("/{adminId}")
    public ResponseEntity<Void> delete(@PathVariable UUID adminId) {
        service.deleteAdmin(adminId);
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
            @Valid @RequestBody ProfileRoleRequest req) {
        return service.assignProfileRole(adminId, req);
    }

    @DeleteMapping("/{adminId}/permissions/profile-roles/{profileId}")
    public ResponseEntity<Void> removeProfileRole(@PathVariable UUID adminId,
                                                   @PathVariable UUID profileId) {
        service.removeProfileRole(adminId, profileId);
        return ResponseEntity.noContent().build();
    }

    // ── Dimension 3: feature permissions ─────────────────────────────────────

    @PutMapping("/{adminId}/permissions/features")
    public ResponseEntity<Void> setFeaturePermissions(
            @PathVariable UUID adminId,
            @RequestBody List<@Valid FeaturePermissionRequest> permissions) {
        service.setFeaturePermissions(adminId, permissions);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{adminId}/permissions/features/{featureKey}")
    public ResponseEntity<Void> clearFeaturePermission(
            @PathVariable UUID adminId,
            @PathVariable FeatureKey featureKey) {
        service.clearFeaturePermission(adminId, featureKey);
        return ResponseEntity.noContent().build();
    }
}
