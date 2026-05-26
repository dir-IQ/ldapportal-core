// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.dashboard.UnifiedDashboardDto;
import com.ldapportal.service.UnifiedDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/summary")
@RequiredArgsConstructor
public class UnifiedDashboardController {

    private final UnifiedDashboardService unifiedDashboardService;

    @GetMapping
    public UnifiedDashboardDto get(@AuthenticationPrincipal AuthPrincipal principal) {
        return unifiedDashboardService.getDashboard(principal);
    }
}
