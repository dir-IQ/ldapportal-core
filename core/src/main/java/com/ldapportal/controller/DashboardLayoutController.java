// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.service.DashboardLayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Per-account dashboard layout CRUD. GET returns an empty object when the
 * account has no saved layout (simpler for the frontend than handling 404);
 * PUT upserts; DELETE resets back to defaults.
 */
@RestController
@RequestMapping("/api/v1/dashboard/layout")
@RequiredArgsConstructor
public class DashboardLayoutController {

    private final DashboardLayoutService service;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(service.get(principal.id()).orElse(Collections.emptyMap()));
    }

    @PutMapping
    public ResponseEntity<Void> save(@AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestBody Map<String, Object> layout) {
        service.save(principal.id(), layout);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(principal.id());
        return ResponseEntity.noContent().build();
    }
}
