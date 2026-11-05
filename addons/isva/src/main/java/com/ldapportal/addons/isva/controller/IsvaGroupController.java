// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.ImportGroupRequest;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrationQuery;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrations;
import com.ldapportal.addons.isva.dto.IsvaGroupStatus;
import com.ldapportal.addons.isva.service.IsvaGroupService;
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
 * Importing groups into IVIA from the admin Groups page — the
 * {@code pdadmin group import} equivalent — and removing them again
 * ({@code pdadmin group delete} without {@code -registry}). Directory-scoped admin verbs:
 * reading needs {@code group.read}, importing {@code group.edit}, and the
 * group DN must be within the caller's scope. Refusals are ProblemDetails
 * with a {@code code} ({@link IsvaAccountExceptionHandler}).
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/isva-group")
@RequiredArgsConstructor
@Entitled(Entitlement.VENDOR_INTEGRATIONS_ISVA)
public class IsvaGroupController {

    private final IsvaGroupService groupService;
    private final PermissionService permissionService;

    @GetMapping
    @RequiresFeature(FeatureKey.GROUP_READ)
    public IsvaGroupStatus getStatus(@DirectoryId @PathVariable UUID directoryId,
                                     @AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return groupService.status(directoryId, dn);
    }

    /** Which of the listed groups are imported — one call for a whole page of groups. */
    @PostMapping("/registrations")
    @RequiresFeature(FeatureKey.GROUP_READ)
    public IsvaGroupRegistrations registrations(@DirectoryId @PathVariable UUID directoryId,
                                                @AuthenticationPrincipal AuthPrincipal principal,
                                                @Valid @RequestBody IsvaGroupRegistrationQuery query) {
        return groupService.registrations(directoryId, query.dns(),
                dn -> permissionService.isDnWithinScope(principal, directoryId, dn));
    }

    /** Removes the group from IVIA (its registration entry); the group itself is unchanged. */
    @PostMapping("/remove")
    @RequiresFeature(FeatureKey.GROUP_EDIT)
    public IsvaGroupStatus removeGroup(@DirectoryId @PathVariable UUID directoryId,
                                       @AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestParam String dn) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return groupService.removeGroup(directoryId, dn, principal);
    }

    @PostMapping("/import")
    @RequiresFeature(FeatureKey.GROUP_EDIT)
    public IsvaGroupStatus importGroup(@DirectoryId @PathVariable UUID directoryId,
                                       @AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestParam String dn,
                                       @Valid @RequestBody(required = false) ImportGroupRequest req) {
        permissionService.requireDnWithinScope(principal, directoryId, dn);
        return groupService.importGroup(directoryId, dn, req != null ? req.name() : null, principal);
    }
}
