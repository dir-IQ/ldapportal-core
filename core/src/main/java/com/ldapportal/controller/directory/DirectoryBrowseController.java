// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.service.LdapOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-only browse endpoint for directory-level users.
 *
 * <pre>
 *   GET /api/v1/directories/{directoryId}/browse?dn=...
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/browse")
@RequiredArgsConstructor
public class DirectoryBrowseController {

    private final LdapOperationService service;

    @GetMapping
    @RequiresFeature(FeatureKey.DIRECTORY_BROWSE)
    public BrowseResult browse(@DirectoryId @PathVariable UUID directoryId,
                               @RequestParam(required = false) String dn,
                               @AuthenticationPrincipal AuthPrincipal principal) {
        return service.browse(directoryId, principal, dn);
    }
}
