// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.dto.settings.ApplicationSettingsDto;
import com.ldapportal.dto.settings.BrandingDto;
import com.ldapportal.dto.settings.UpdateApplicationSettingsRequest;
import com.ldapportal.service.ApplicationSettingsService;
import com.ldapportal.core.siem.service.SiemExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Global application settings.
 *
 * <pre>
 *   GET /api/v1/settings  — returns current settings (or defaults if not yet configured)
 *   PUT /api/v1/settings  — create or replace settings
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class ApplicationSettingsController {

    private final ApplicationSettingsService service;
    private final SiemExportService          siemExportService;

    /** Returns current settings (superadmin only). */
    @GetMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ApplicationSettingsDto get() {
        return service.get();
    }

    /** Returns only branding fields (public — no auth required). */
    @GetMapping("/branding")
    public BrandingDto getBranding() {
        return service.getBranding();
    }

    @PutMapping
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ApplicationSettingsDto upsert(@Valid @RequestBody UpdateApplicationSettingsRequest req) {
        ApplicationSettingsDto result = service.upsert(req);
        siemExportService.invalidateCache();
        return result;
    }

    /** Marks first-run setup as complete. Called by the setup wizard on the final step. */
    @PostMapping("/complete-setup")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ApplicationSettingsDto completeSetup() {
        return service.markSetupComplete();
    }

    /** Tests SIEM connectivity by sending a synthetic audit event. */
    @PostMapping("/siem/test")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public Map<String, String> testSiem() {
        String connectivity = siemExportService.testConnectivity();
        String delivery = siemExportService.sendTestEvent();
        return Map.of("connectivity", connectivity, "delivery", delivery);
    }

    /**
     * Exports historical audit events to SIEM for a given date range.
     * Useful when SIEM is enabled after events have already been recorded.
     */
    @PostMapping("/siem/backfill")
    @PreAuthorize("hasRole('SUPERADMIN')")
    public Map<String, Object> siemBackfill(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        int exported = siemExportService.backfill(from, to);
        return Map.of("exported", exported, "from", from.toString(), "to", to.toString());
    }
}
