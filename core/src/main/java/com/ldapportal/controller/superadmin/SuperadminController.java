// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.superadmin.CreateSuperadminRequest;
import com.ldapportal.dto.superadmin.ResetPasswordRequest;
import com.ldapportal.dto.superadmin.SuperadminResponse;
import com.ldapportal.dto.superadmin.UpdateSuperadminRequest;
import com.ldapportal.service.SuperadminManagementService;
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
@RequiredArgsConstructor
public class SuperadminController {

    private final SuperadminManagementService service;

    @GetMapping
    public List<SuperadminResponse> list() {
        return service.listSuperadmins();
    }

    @PostMapping
    public ResponseEntity<SuperadminResponse> create(
            @Valid @RequestBody CreateSuperadminRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSuperadmin(req));
    }

    @GetMapping("/{id}")
    public SuperadminResponse get(@PathVariable UUID id) {
        return service.getSuperadmin(id);
    }

    @PutMapping("/{id}")
    public SuperadminResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateSuperadminRequest req) {
        return service.updateSuperadmin(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteSuperadmin(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req);
        return ResponseEntity.noContent().build();
    }
}
