// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The user-preferences framework's single API surface — one place that persists
 * every UI customization an account makes (theme, density, table column state,
 * saved filters, search history, modal sizes, sidebar, ...). The frontend used
 * to keep these in browser localStorage; they now live server-side so they
 * follow the user across browsers and devices.
 *
 * <pre>
 *   GET    /api/v1/me/preferences              — full preferences document
 *   PATCH  /api/v1/me/preferences              — merge-patch (RFC 7386) a partial document
 *   PUT    /api/v1/me/preferences/{namespace}  — replace one namespace wholesale
 *   DELETE /api/v1/me/preferences/{namespace}  — reset one namespace to defaults
 * </pre>
 *
 * <p>Only real accounts (superadmin / admin) reach this surface — Spring
 * Security restricts {@code /api/v1/**} to those roles, so self-service LDAP
 * principals (who have no {@code accounts} row to key on) are excluded by
 * construction.</p>
 */
@RestController
@RequestMapping("/api/v1/me/preferences")
@RequiredArgsConstructor
public class PreferencesController {

    private final UserPreferencesService service;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(service.get(require(principal).id()));
    }

    /** Merge a partial document. The body's top-level keys must be known
     *  namespaces; a {@code null} value within the patch deletes that key. */
    @PatchMapping
    public ResponseEntity<Map<String, Object>> patch(@AuthenticationPrincipal AuthPrincipal principal,
                                                     @RequestBody JsonNode patch) {
        return ResponseEntity.ok(service.applyMergePatch(require(principal).id(), patch));
    }

    @PutMapping("/{namespace}")
    public ResponseEntity<Map<String, Object>> putNamespace(@AuthenticationPrincipal AuthPrincipal principal,
                                                            @PathVariable String namespace,
                                                            @RequestBody JsonNode body) {
        return ResponseEntity.ok(service.replaceNamespace(require(principal).id(), namespace, body));
    }

    @DeleteMapping("/{namespace}")
    public ResponseEntity<Map<String, Object>> deleteNamespace(@AuthenticationPrincipal AuthPrincipal principal,
                                                               @PathVariable String namespace) {
        return ResponseEntity.ok(service.clearNamespace(require(principal).id(), namespace));
    }

    private AuthPrincipal require(AuthPrincipal principal) {
        if (principal == null) throw new BadCredentialsException("Not authenticated");
        return principal;
    }
}
