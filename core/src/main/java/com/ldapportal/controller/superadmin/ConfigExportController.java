// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.service.ConfigExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exports the portal's own configuration as declarative YAML for disaster
 * recovery and infrastructure-as-code — the inverse of the startup
 * {@code BootstrapConfigReconciler}.
 *
 * <pre>
 *   GET /api/v1/superadmin/config/export  — directories, admins, vendor config
 * </pre>
 *
 * <p>Superadmin-only (the whole config plane is), and mounted under
 * {@code /api/v1/superadmin/**} so the URL-level role rule applies in addition
 * to the method annotation. The response is a {@code bootstrap-config.yml} the
 * operator can commit to Git or hand back to a fresh install via
 * {@code BOOTSTRAP_CONFIG_FILE}. Secrets are emitted as {@code ${ENV_VAR}}
 * placeholders — see {@link ConfigExportService}.</p>
 */
@RestController
@RequestMapping("/api/v1/superadmin/config")
@RequiredArgsConstructor
public class ConfigExportController {

    /** Media type for the YAML body (springdoc/Spring lack a YAML constant). */
    private static final String APPLICATION_YAML = "application/yaml";

    private final ConfigExportService configExportService;

    @GetMapping(value = "/export", produces = APPLICATION_YAML)
    @PreAuthorize("hasRole('SUPERADMIN')")
    public ResponseEntity<String> export() {
        String yaml = configExportService.exportYaml();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bootstrap-config.yml\"")
                .contentType(MediaType.parseMediaType(APPLICATION_YAML))
                .body(yaml);
    }
}
