// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;
import java.util.Map;

/**
 * What the engine uses for each sync-set field left blank, so the editor can
 * show the real defaults instead of copies that could drift.
 *
 * @param identityKeyByDirectoryType  the identity attribute per source directory
 *                                    type ({@code DirectoryType} name → attribute)
 * @param referenceAttributes         the DN-valued attributes remapped and
 *                                    closed over when none are configured
 * @param reconcileIntervalSeconds    the reconcile interval when none is set
 *                                    (this server's configuration)
 * @param excludedAttributes          the attributes never synced when no
 *                                    exclusions are configured
 */
public record SyncSetDefaults(
        Map<String, String> identityKeyByDirectoryType,
        List<String> referenceAttributes,
        long reconcileIntervalSeconds,
        List<String> excludedAttributes) {
}
