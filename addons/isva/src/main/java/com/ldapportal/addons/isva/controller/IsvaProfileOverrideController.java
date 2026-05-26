// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaProfileOverrideDto;
import com.ldapportal.addons.isva.entity.IsvaProfileOverride;
import com.ldapportal.addons.isva.service.IsvaProfileOverrideService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.ProvisioningProfileRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-profile ISVA override (narrowing-only — {@code INHERIT} |
 * {@code FORCE_OFF}). Layered on the per-directory
 * {@link IsvaConfigController} config.
 *
 * <pre>
 *   GET  /api/v1/directories/{directoryId}/profiles/{profileId}/isva-override
 *   PUT  /api/v1/directories/{directoryId}/profiles/{profileId}/isva-override
 * </pre>
 *
 * <p>Superadmin-only. The class-level {@link Entitled} annotation also
 * gates on {@code VENDOR_INTEGRATIONS_ISVA}, so community deployments
 * (no addon on the classpath) respond 403; the frontend hides the
 * control via the same entitlement flag.</p>
 *
 * <p>{@code directoryId} is REST-nesting context only — the override
 * is keyed by {@code profileId} alone. A GET for a profile with no row
 * returns {@code INHERIT}.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/profiles/{profileId}/isva-override")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaProfileOverrideController {

    private final IsvaProfileOverrideService service;
    private final ProvisioningProfileRepository profileRepo;

    @GetMapping
    public ResponseEntity<IsvaProfileOverrideDto> get(@PathVariable UUID directoryId,
                                                      @PathVariable UUID profileId) {
        assertProfileInDirectory(directoryId, profileId);
        return ResponseEntity.ok(IsvaProfileOverrideDto.of(service.getOverride(profileId)));
    }

    @PutMapping
    public ResponseEntity<IsvaProfileOverrideDto> set(@PathVariable UUID directoryId,
                                                      @PathVariable UUID profileId,
                                                      @AuthenticationPrincipal AuthPrincipal principal,
                                                      @Valid @RequestBody IsvaProfileOverrideDto req) {
        // Guard the FK before the upsert: an unknown profile id (or one
        // belonging to a different directory than the path says) would
        // otherwise blow up as a DataIntegrityViolation/500 on INSERT.
        assertProfileInDirectory(directoryId, profileId);
        IsvaProfileOverride saved = service.setOverride(profileId, req.override(),
                principal != null ? principal.username() : "system");
        return ResponseEntity.ok(IsvaProfileOverrideDto.of(saved));
    }

    private void assertProfileInDirectory(UUID directoryId, UUID profileId) {
        if (profileRepo.findByIdAndDirectoryId(profileId, directoryId).isEmpty()) {
            throw new ResourceNotFoundException(
                    "No profile " + profileId + " in directory " + directoryId);
        }
    }
}
