// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.service.ActivityDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles suggestion-dismissal writes. The former GET /api/v1/dashboard/activity
 * endpoint was replaced by the unified GET /api/v1/dashboard/summary (see
 * {@link UnifiedDashboardController}). The dismissal POST stays here because
 * it's scoped to an individual user's suggestion preferences, not the
 * aggregated read path.
 */
@RestController
@RequestMapping("/api/v1/dashboard/activity")
@RequiredArgsConstructor
public class ActivityDashboardController {

    private final ActivityDashboardService activityService;

    @PostMapping("/dismiss/{key}")
    public void dismiss(@AuthenticationPrincipal AuthPrincipal principal,
                        @PathVariable String key) {
        activityService.dismissSuggestion(principal.id(), key);
    }
}
