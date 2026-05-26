// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.IsvaUiOptions;
import com.ldapportal.addons.isva.dto.IsvaUiOptionsDto;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global (not per-directory) UI options for the ISVA integration page —
 * currently which topology modes to offer (env-driven; see
 * {@link IsvaUiOptions}). Deployment-static, so the frontend fetches it
 * once and caches.
 *
 * <pre>
 *   GET /api/v1/isva/ui-options
 * </pre>
 *
 * <p>Superadmin-only and gated on {@code VENDOR_INTEGRATIONS_ISVA} — same
 * as {@link IsvaConfigController}, so community / non-addon builds 403.</p>
 */
@RestController
@RequestMapping("/api/v1/isva/ui-options")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaUiOptionsController {

    private final IsvaUiOptions uiOptions;

    @GetMapping
    public ResponseEntity<IsvaUiOptionsDto> get() {
        return ResponseEntity.ok(new IsvaUiOptionsDto(uiOptions.resolvedTopologyModes()));
    }
}
