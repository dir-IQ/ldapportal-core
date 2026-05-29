// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigDto;
import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.addons.isva.service.IsvaConfigProbeService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.DirectoryConnectionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-directory ISVA full-mode integration configuration.
 *
 * <pre>
 *   GET    /api/v1/directories/{id}/isva-config        — read (404 if absent)
 *   PUT    /api/v1/directories/{id}/isva-config        — upsert (SUPERADMIN)
 *   POST   /api/v1/directories/{id}/isva-config/probe  — health check (SUPERADMIN)
 * </pre>
 *
 * <p>Page-level UI options (which topology modes to offer) are global and
 * env-driven — see {@link IsvaUiOptionsController}.</p>
 *
 * <p>Class-level {@link Entitled} gates on {@code VENDOR_INTEGRATIONS_ISVA}
 * being granted — community deployments without the addon classpath
 * respond 403 on every endpoint. The frontend hides the panel via the
 * same entitlement flag so operators never see a button that can't be
 * used.</p>
 *
 * <p><strong>Per-method authz:</strong> GET is readable by any authenticated
 * principal (ADMIN, SUPERADMIN). PUT / probe stay superadmin-only —
 * they mutate directory-wide IVIA policy. Lower-privilege admin reads
 * are necessary because the IVIA account panel and the user-form's
 * tab-button gate both fetch GET to decide whether to render anything;
 * a class-level superadmin gate would 403 the gating call for admins
 * and make the panel invisible to them. The config fields are admin-
 * policy (management-DIT base DN, sec authority, topology mode, etc) —
 * not credentials — and the same information is leakable indirectly
 * via {@code IsvaUserReadEnricher}'s row tags. Returning it to admins
 * doesn't widen the disclosure surface.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/isva-config")
@RequiredArgsConstructor
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaConfigController {

    private final VendorIntegrationIsvaConfigRepository configRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IsvaConfigProbeService probeService;

    @GetMapping
    public ResponseEntity<IsvaConfigDto> get(@PathVariable UUID directoryId) {
        assertDirectoryExists(directoryId);
        return configRepo.findById(directoryId)
                .map(IsvaConfigDto::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ISVA configuration exists for directory " + directoryId
                                + ". PUT a config to create one."));
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<IsvaConfigDto> upsert(
            @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpsertIsvaConfigRequest req) {

        assertDirectoryExists(directoryId);
        validateLinkedModeFields(req);

        VendorIntegrationIsvaConfig entity = configRepo.findById(directoryId)
                .orElseGet(VendorIntegrationIsvaConfig::new);
        entity.setDirectoryConnectionId(directoryId);
        entity.setEnabled(req.enabled());
        entity.setTopologyMode(req.topologyMode());
        entity.setSecAuthority(blankToNull(req.secAuthority()));
        entity.setDefaultValidUntilYears(req.defaultValidUntilYears());
        entity.setDeletePolicy(req.deletePolicy());
        entity.setRequireSecGroup(req.requireSecGroup());

        // Linked-mode fields — set when LINKED, null when INLINE so
        // a topology-mode flip doesn't leave stale linked config
        // around to confuse the probe / interceptor.
        if (req.topologyMode() == IsvaTopologyMode.LINKED) {
            entity.setManagementDitBaseDn(req.managementDitBaseDn().trim());
            entity.setSecuserRdnAttribute(blankOrDefault(req.secuserRdnAttribute(), "secUUID"));
            entity.setGroupMemberTarget(req.groupMemberTarget() != null
                    ? req.groupMemberTarget() : entity.getGroupMemberTarget());
            entity.setOnDemographicDelete(req.onDemographicDelete() != null
                    ? req.onDemographicDelete() : entity.getOnDemographicDelete());
        } else {
            entity.setManagementDitBaseDn(null);
            // Leave secuserRdnAttribute / groupMemberTarget /
            // onDemographicDelete at their stored defaults — they're
            // ignored in INLINE mode anyway and clearing them would
            // be unnecessary churn against the audit columns.
        }

        entity.setUpdatedBy(principal != null ? principal.username() : "system");
        VendorIntegrationIsvaConfig saved = configRepo.save(entity);
        return ResponseEntity.ok(IsvaConfigDto.from(saved));
    }

    @PostMapping("/probe")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<ProbeResult> probe(@PathVariable UUID directoryId) {
        DirectoryConnection dir = directoryRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Directory not found"));
        VendorIntegrationIsvaConfig cfg = configRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ISVA configuration exists for directory " + directoryId
                                + " — save a config before probing."));
        return ResponseEntity.ok(probeService.probe(dir, cfg));
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void assertDirectoryExists(UUID directoryId) {
        if (!directoryRepo.existsById(directoryId)) {
            throw new ResourceNotFoundException("Directory not found");
        }
    }

    /**
     * Defence in depth — DB has a matching CHECK constraint, but
     * surfacing the validation at the API layer gives the operator
     * a friendly error instead of a 500-with-stack-trace.
     *
     * <p>{@link IllegalArgumentException} (not {@code ResponseStatusException})
     * because the core {@code GlobalExceptionHandler} maps the former to
     * a 400 ProblemDetail with the message as {@code detail}; the latter
     * falls through to the catch-all and surfaces as 500.</p>
     */
    private static void validateLinkedModeFields(UpsertIsvaConfigRequest req) {
        if (req.topologyMode() == IsvaTopologyMode.LINKED
                && (req.managementDitBaseDn() == null
                    || req.managementDitBaseDn().isBlank())) {
            throw new IllegalArgumentException(
                    "managementDitBaseDn is required when topologyMode is LINKED");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String blankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
