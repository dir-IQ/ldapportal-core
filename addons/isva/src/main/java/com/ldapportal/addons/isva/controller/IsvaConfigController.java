// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigDto;
import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
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
 * Per-directory ISVA full-mode integration configuration, addressed by the
 * directory's surrogate id.
 *
 * <pre>
 *   GET    /api/v1/directories/{id}/isva-config        — read (404 if absent)
 *   PUT    /api/v1/directories/{id}/isva-config        — upsert (SUPERADMIN)
 *   POST   /api/v1/directories/{id}/isva-config/probe  — health check (SUPERADMIN)
 * </pre>
 *
 * <p>{@code IsvaConfigBySlugController} mirrors these under
 * {@code /api/v1/directories/by-slug/{slug}/isva-config} so IaC automation
 * can target a directory by its stable slug rather than its server-assigned
 * id. Both controllers are thin delegates over {@link IsvaConfigService}.</p>
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

    private final IsvaConfigService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<IsvaConfigDto> get(@PathVariable UUID directoryId) {
        return ResponseEntity.ok(service.get(directoryId));
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<IsvaConfigDto> upsert(
            @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpsertIsvaConfigRequest req) {
        return ResponseEntity.ok(service.upsert(directoryId, req, principal));
    }

    @PostMapping("/probe")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<ProbeResult> probe(@PathVariable UUID directoryId) {
        return ResponseEntity.ok(service.probe(directoryId));
    }
}
