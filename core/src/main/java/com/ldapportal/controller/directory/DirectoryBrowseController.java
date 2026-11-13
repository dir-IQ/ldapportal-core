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
 *   GET /api/v1/directories/{directoryId}/browse?dn=...&amp;limit=...
 * </pre>
 *
 * {@code limit} caps the children returned with the entry, as on the
 * superadmin browse endpoint. Omitted or {@code 0} means all (subject to the
 * service's hard ceiling); callers that only want the entry's attributes,
 * such as the DN quick-look drawer, pass {@code 1}. Negative is a 400.
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
                               @RequestParam(required = false) Integer limit,
                               @AuthenticationPrincipal AuthPrincipal principal) {
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit must be zero or positive");
        }
        return service.browse(directoryId, principal, dn, limit == null ? 0 : limit);
    }
}
