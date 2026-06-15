// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.dto.RenewRequest;
import com.ldapportal.addons.isva.dto.RevokeRequest;
import com.ldapportal.addons.isva.service.IsvaAccountService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.entity.enums.FeatureKey;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account-scoped REST surface for IVIA verbs. Mirrors
 * {@link com.ldapportal.controller.directory.UserController}'s shape
 * (directory-scoped, per-method {@code @RequiresFeature}, DN-scope
 * check) so an admin already provisioned for user lifecycle ops gets
 * the IVIA verbs for free on the same OUs they already manage.
 *
 * <h2>URL shape</h2>
 * <pre>
 *   GET    /api/v1/directories/{directoryId}/isva-account?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/grant?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/revoke?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/suspend?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/restore?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/renew?dn={dn}
 *   POST   /api/v1/directories/{directoryId}/isva-account/force-credential-reset?dn={dn}
 * </pre>
 *
 * <p>The DN sits as a {@code @RequestParam} rather than a path
 * segment because DNs contain {@code =}, {@code ,}, and {@code +} —
 * all reserved characters that would otherwise have to ride through
 * the URL via repeated percent-encoding. Other addon endpoints
 * ({@code IsvaConfigController.probe}, {@code AuditLogController}'s
 * DN filter) take the same shape.</p>
 *
 * <h2>Authorisation</h2>
 *
 * <p>Class-level {@link Entitled} gates on
 * {@link Entitlement#VENDOR_INTEGRATIONS_ISVA} — community deployments
 * without the addon-classpath are out of scope and respond 403 here.
 * Per-method {@link RequiresFeature} picks the corresponding
 * {@code USER_*} feature key — read for {@code getStatus},
 * enable/disable for grant / revoke / suspend / restore, edit for
 * renew, reset-password for force-credential-reset. The DN is
 * verified against the admin's OU scope on every call via
 * {@link PermissionService#requireDnWithinScope}.</p>
 *
 * <p>Refusals surface as RFC 7807 ProblemDetail with a {@code code}
 * property — see {@link IsvaAccountExceptionHandler}.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/isva-account")
@RequiredArgsConstructor
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaAccountController {

    private final IsvaAccountService accountService;
    private final PermissionService permissionService;

    @GetMapping
    @RequiresFeature(FeatureKey.USER_READ)
    public IsvaAccountStatus getStatus(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.getStatus(directoryId, dn);
    }

    @PostMapping("/grant")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public IsvaAccountStatus grant(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.grant(directoryId, dn, principal);
    }

    @PostMapping("/revoke")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public IsvaAccountStatus revoke(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody RevokeRequest req) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.revoke(directoryId, dn, req.mode(), principal);
    }

    @PostMapping("/suspend")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public IsvaAccountStatus suspend(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.suspend(directoryId, dn, principal);
    }

    @PostMapping("/restore")
    @RequiresFeature(FeatureKey.USER_ENABLE_DISABLE)
    public IsvaAccountStatus restore(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.restore(directoryId, dn, principal);
    }

    @PostMapping("/renew")
    @RequiresFeature(FeatureKey.USER_EDIT)
    public IsvaAccountStatus renew(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn,
            @Valid @RequestBody RenewRequest req) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.renew(directoryId, dn, req.validUntil(), principal);
    }

    @PostMapping("/force-credential-reset")
    @RequiresFeature(FeatureKey.USER_RESET_PASSWORD)
    public IsvaAccountStatus forceCredentialReset(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return accountService.forceCredentialReset(directoryId, dn, principal);
    }
}
