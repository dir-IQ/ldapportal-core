// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.dto.replication.RewindChangelogRequest;
import com.ldapportal.service.ReplicationLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Operator remediation for changelog-capture links (design §7A.12) — the
 * one-click recovery controls that turn a 2 a.m. incident into a button press
 * instead of DB surgery. SUPERADMIN + DIRECTORY_SYNC, all audited.
 *
 * <pre>
 *   POST /api/v1/superadmin/replication-links/{id}/changelog/reseed     — cursor → re-seed from current head
 *   POST /api/v1/superadmin/replication-links/{id}/changelog/rewind     — cursor → operator-supplied changeNumber
 *   POST /api/v1/superadmin/replication-links/{id}/changelog/re-enable  — clear a degraded health/error, retry
 * </pre>
 *
 * <p>Force-reconcile lives on {@code ReconciliationController} as
 * {@code POST …/{id}/reconcile}.
 */
@RestController
@PreAuthorize("hasRole('SUPERADMIN')")
@Entitled(Entitlement.DIRECTORY_SYNC)
@RequiredArgsConstructor
public class ChangelogRemediationController {

    private final ReplicationLinkService service;

    @PostMapping("/api/v1/superadmin/replication-links/{id}/changelog/reseed")
    public ReplicationLinkResponse reseed(@AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID id) {
        return service.reseedChangelogCursor(principal, id);
    }

    @PostMapping("/api/v1/superadmin/replication-links/{id}/changelog/rewind")
    public ReplicationLinkResponse rewind(@AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody RewindChangelogRequest req) {
        return service.rewindChangelogCursor(principal, id, req.changeNumber());
    }

    @PostMapping("/api/v1/superadmin/replication-links/{id}/changelog/re-enable")
    public ReplicationLinkResponse reEnable(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID id) {
        return service.reEnableChangelogPoll(principal, id);
    }
}
