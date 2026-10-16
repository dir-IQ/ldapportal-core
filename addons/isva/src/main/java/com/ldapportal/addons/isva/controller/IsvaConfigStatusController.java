// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigStatusDto;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.entity.enums.SuperadminPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Which directories have an ISVA configuration, and whether it is enabled —
 * one call for the Directory Connections list instead of a per-row GET of
 * {@link IsvaConfigController} (which 404s for unconfigured directories).
 *
 * <pre>
 *   GET /api/v1/isva/config-status
 * </pre>
 *
 * <p>Superadmin-only and gated on {@code VENDOR_INTEGRATIONS_ISVA} — same
 * as {@link IsvaUiOptionsController}, so community / non-addon builds 403.</p>
 */
@RestController
@RequestMapping("/api/v1/isva/config-status")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.VIEW_INTEGRATIONS)
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaConfigStatusController {

    private final IsvaConfigService service;

    @GetMapping
    public ResponseEntity<List<IsvaConfigStatusDto>> list() {
        return ResponseEntity.ok(service.listStatuses());
    }
}
