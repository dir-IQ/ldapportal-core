// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;

import java.util.UUID;

/**
 * The identity of a directory connection — what a directory picker needs and
 * nothing more. Served to <em>every</em> superadmin, unlike
 * {@link DirectoryConnectionResponse}, which carries the connection
 * configuration (host, bind DN, pool settings, …) and is gated by
 * {@code VIEW_DIRECTORIES}. Pages outside the Directory Connections area
 * (browser, search, schema, reports, sync, profiles, …) list directories
 * through this so a scoped superadmin can still pick one.
 */
public record DirectorySummaryResponse(
        UUID id,
        String slug,
        DirectoryType directoryType,
        String displayName,
        boolean enabled) {

    public static DirectorySummaryResponse from(DirectoryConnection dc) {
        return new DirectorySummaryResponse(
                dc.getId(), dc.getSlug(), dc.getDirectoryType(), dc.getDisplayName(), dc.isEnabled());
    }
}
