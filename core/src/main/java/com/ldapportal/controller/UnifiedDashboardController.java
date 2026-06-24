// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.dashboard.UnifiedDashboardDto;
import com.ldapportal.service.UnifiedDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/summary")
@RequiredArgsConstructor
public class UnifiedDashboardController {

    private final UnifiedDashboardService unifiedDashboardService;

    /**
     * @param includeScopeCounts when {@code false}, the LDAP user/group counts
     *        are skipped so the response returns without waiting on LDAP. The
     *        client uses this for a fast first paint, then re-requests with the
     *        default ({@code true}) to fill the counts in. Defaults to
     *        {@code true} so existing API consumers are unaffected.
     */
    @GetMapping
    public UnifiedDashboardDto get(@AuthenticationPrincipal AuthPrincipal principal,
                                   @RequestParam(name = "includeScopeCounts", defaultValue = "true")
                                   boolean includeScopeCounts) {
        return unifiedDashboardService.getDashboard(principal, includeScopeCounts);
    }
}
