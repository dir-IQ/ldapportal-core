// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.dto.superadmin.CreateSuperadminRequest;
import com.ldapportal.dto.superadmin.ResetPasswordRequest;
import com.ldapportal.dto.superadmin.SuperadminPermissionsDto;
import com.ldapportal.dto.superadmin.SuperadminResponse;
import com.ldapportal.dto.superadmin.UpdateSuperadminPermissionsRequest;
import com.ldapportal.dto.superadmin.UpdateSuperadminRequest;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.service.SuperadminManagementService;
import com.ldapportal.service.SuperadminPermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Platform-level superadmin account management.
 *
 * <pre>
 *   GET    /api/superadmin/superadmins           — list all
 *   POST   /api/superadmin/superadmins           — create LOCAL superadmin
 *   GET    /api/superadmin/superadmins/{id}      — get by ID
 *   PUT    /api/superadmin/superadmins/{id}      — update (display name, email, active)
 *   DELETE /api/superadmin/superadmins/{id}      — delete
 *   POST   /api/superadmin/superadmins/{id}/reset-password — reset password
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/superadmin/superadmins")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.MANAGE_SUPERADMINS)
@RequiredArgsConstructor
public class SuperadminController {

    private final SuperadminManagementService service;
    private final SuperadminPermissionService superadminPermissionService;

    @GetMapping
    public List<SuperadminResponse> list() {
        return service.listSuperadmins();
    }

    @PostMapping
    public ResponseEntity<SuperadminResponse> create(
            @Valid @RequestBody CreateSuperadminRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.ldapportal.auth.AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createSuperadmin(req, principal));
    }

    @GetMapping("/{id}")
    public SuperadminResponse get(@PathVariable UUID id) {
        return service.getSuperadmin(id);
    }

    @PutMapping("/{id}")
    public SuperadminResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateSuperadminRequest req,
                                     @org.springframework.security.core.annotation.AuthenticationPrincipal
                                             com.ldapportal.auth.AuthPrincipal principal) {
        return service.updateSuperadmin(id, req, principal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                        @org.springframework.security.core.annotation.AuthenticationPrincipal
                                                com.ldapportal.auth.AuthPrincipal principal) {
        service.deleteSuperadmin(id, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest req,
                                              @org.springframework.security.core.annotation.AuthenticationPrincipal
                                                      com.ldapportal.auth.AuthPrincipal principal) {
        service.resetPassword(id, req, principal);
        return ResponseEntity.noContent().build();
    }

    // ── Superadmin permission grants (owner-only via the class-level gate) ──────

    /** The account's permission state for the editor (catalogue + granted + effective). */
    @GetMapping("/{id}/permissions")
    public SuperadminPermissionsDto getPermissions(@PathVariable UUID id) {
        Set<SuperadminPermission> granted = superadminPermissionService.granted(id);
        Set<SuperadminPermission> effective = superadminPermissionService.effective(id);
        return new SuperadminPermissionsDto(
                Arrays.stream(SuperadminPermission.values()).map(SuperadminPermission::getDbValue).toList(),
                granted.stream().map(SuperadminPermission::getDbValue).sorted().toList(),
                effective.stream().map(SuperadminPermission::getDbValue).sorted().toList(),
                granted.contains(SuperadminPermission.MANAGE_SUPERADMINS));
    }

    /** Replace the account's granted permission set. Refuses to drop the last owner. */
    @PutMapping("/{id}/permissions")
    public SuperadminPermissionsDto setPermissions(@PathVariable UUID id,
                                                   @Valid @RequestBody UpdateSuperadminPermissionsRequest req,
                                                   @AuthenticationPrincipal AuthPrincipal principal) {
        List<String> keys = req.permissions() == null ? List.of() : req.permissions();
        Set<SuperadminPermission> desired = keys.stream()
                .map(SuperadminPermission::fromDbValue)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SuperadminPermission.class)));
        superadminPermissionService.replacePermissions(id, desired, principal);
        return getPermissions(id);
    }
}
