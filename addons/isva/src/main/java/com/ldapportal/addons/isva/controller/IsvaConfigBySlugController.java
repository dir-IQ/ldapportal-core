// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigDto;
import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.dto.UpsertIsvaConfigRequest;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.web.ETagSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slug-addressed mirror of {@link IsvaConfigController} for IaC automation.
 * Automation declares directories by their stable {@code slug} (see the
 * core directory upsert), not by the server-assigned UUID, so it needs to
 * reach a directory's ISVA config the same way.
 *
 * <pre>
 *   GET    /api/v1/directories/by-slug/{slug}/isva-config        — read (404 if absent)
 *   PUT    /api/v1/directories/by-slug/{slug}/isva-config        — upsert (SUPERADMIN)
 *   POST   /api/v1/directories/by-slug/{slug}/isva-config/probe  — health check (SUPERADMIN)
 * </pre>
 *
 * <p>Each method resolves the slug to the directory id via
 * {@link IsvaConfigService#resolveDirectoryIdBySlug(String)} (404 on an
 * unknown slug) and then runs the same id-keyed logic, so the upsert is
 * idempotent and the entitlement / authz gates match the id-addressed
 * controller exactly.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/by-slug/{slug}/isva-config")
@RequiredArgsConstructor
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaConfigBySlugController {

    private final IsvaConfigService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERADMIN')")
    public ResponseEntity<IsvaConfigDto> get(@PathVariable String slug) {
        IsvaConfigDto dto = service.get(service.resolveDirectoryIdBySlug(slug));
        return ResponseEntity.ok().eTag(ETagSupport.format(dto.version())).body(dto);
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<IsvaConfigDto> upsert(
            @PathVariable String slug,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpsertIsvaConfigRequest req) {
        IsvaConfigDto dto = service.upsert(
                service.resolveDirectoryIdBySlug(slug), req, principal, ETagSupport.parseIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(ETagSupport.format(dto.version())).body(dto);
    }

    @PostMapping("/probe")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<ProbeResult> probe(@PathVariable String slug) {
        return ResponseEntity.ok(service.probe(service.resolveDirectoryIdBySlug(slug)));
    }
}
